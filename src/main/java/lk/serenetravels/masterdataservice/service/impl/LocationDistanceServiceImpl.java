package lk.serenetravels.masterdataservice.service.impl;

import lk.serenetravels.masterdataservice.dto.LocationDistanceResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.LocationDistanceService;
import lk.serenetravels.masterdataservice.util.ColumnValues;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Column;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Type;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class LocationDistanceServiceImpl implements LocationDistanceService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final BigDecimal MAX_DISTANCE_KM = new BigDecimal("5000");
    private static final int MAX_DURATION_MINUTES = 2880;      // two days
    // The database does not restrict route_type (legacy data uses ROAD, ROADA, ROADB); keep it tidy and comparable.
    private static final Pattern ROUTE_TYPE = Pattern.compile("[A-Z0-9_]{1,30}");

    private static final List<Column> COLUMNS = List.of(
            new Column("fromDestinationId", "from_destination_id", Type.INT, true),
            new Column("toDestinationId", "to_destination_id", Type.INT, true),
            new Column("routeType", "route_type", Type.TEXT, true),
            new Column("distanceKm", "distance_km", Type.DECIMAL, true),
            new Column("durationMinutes", "duration_minutes", Type.INT, false));

    private static final Map<String, String> SORTS = Map.of("from", "f.name", "to", "t.name",
            "routeType", "l.route_type", "distanceKm", "l.distance_km", "durationMinutes", "l.duration_minutes", "id", "l.id");

    private static final String SELECT = """
            select l.id, l.from_destination_id, f.name as from_name, l.to_destination_id, t.name as to_name,
                   l.route_type, l.distance_km, l.duration_minutes
              from location_distance l
              join destination f on f.id = l.from_destination_id
              join destination t on t.id = l.to_destination_id""";

    private final JdbcClient jdbc;

    public LocationDistanceServiceImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public PageResponse<LocationDistanceResponse> list(String q, Integer fromDestinationId, Integer toDestinationId,
                                                       Integer destinationId, String routeType, Boolean needsReview,
                                                       List<String> sort, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        Map<String, Object> params = new HashMap<>();
        List<String> where = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where.add("(lower(f.name) like :q escape '\\' or lower(t.name) like :q escape '\\')");
            params.put("q", "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (fromDestinationId != null) {
            where.add("l.from_destination_id = :from");
            params.put("from", fromDestinationId);
        }
        if (toDestinationId != null) {
            where.add("l.to_destination_id = :to");
            params.put("to", toDestinationId);
        }
        if (destinationId != null) {
            where.add("(l.from_destination_id = :either or l.to_destination_id = :either)");
            params.put("either", destinationId);
        }
        if (routeType != null && !routeType.isBlank()) {
            where.add("l.route_type = :routeType");
            params.put("routeType", routeType.trim().toUpperCase());
        }
        if (needsReview != null) {
            String suspect = "(l.from_destination_id = l.to_destination_id or l.distance_km <= 0)";
            where.add(needsReview ? suspect : "not " + suspect);
        }
        String whereSql = where.isEmpty() ? "" : " where " + String.join(" and ", where);

        Long total = jdbc.sql("""
                        select count(*) from location_distance l
                          join destination f on f.id = l.from_destination_id
                          join destination t on t.id = l.to_destination_id""" + whereSql)
                .params(params).query(Long.class).single();

        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<LocationDistanceResponse> rows = jdbc.sql(SELECT + whereSql + " order by " + orderBy(sort) + " limit :limit offset :offset")
                .params(params).query((rs, n) -> map(rs)).list();
        return PageResponse.of(rows, total, page, size);
    }

    @Override
    public LocationDistanceResponse get(int id) {
        return jdbc.sql(SELECT + " where l.id = :id").param("id", id).query((rs, n) -> map(rs))
                .optional().orElseThrow(() -> notFound(id));
    }

    @Override
    public List<LocationDistanceResponse> between(int destinationA, int destinationB) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!destinationExists(destinationA)) {
            errors.put("fromDestinationId", "is not a known destination");
        }
        if (!destinationExists(destinationB)) {
            errors.put("toDestinationId", "is not a known destination");
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }
        return jdbc.sql(SELECT + """

                         where (l.from_destination_id = :a and l.to_destination_id = :b)
                            or (l.from_destination_id = :b and l.to_destination_id = :a)
                         order by l.distance_km asc, l.route_type asc, l.id asc""")
                .param("a", destinationA).param("b", destinationB).query((rs, n) -> map(rs)).list();
    }

    // ------------------------------------------------------------------ writes

    @Override
    @Transactional
    public LocationDistanceResponse create(Map<String, Object> body) {
        Map<String, Object> values = ColumnValues.coerce("location distance", COLUMNS, body, true);
        normalise(values);
        validate(values);
        checkNotDuplicate(values, null);

        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            columns.add(column(json).db());
            placeholders.add(":" + json);
            params.put(json, value);
        });
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("insert into location_distance (" + String.join(", ", columns) + ") values ("
                + String.join(", ", placeholders) + ")").params(params).update(keyHolder, "id");
        return get(keyHolder.getKey().intValue());
    }

    @Override
    @Transactional
    public LocationDistanceResponse update(int id, Map<String, Object> body) {
        LocationDistanceResponse existing = get(id);
        Map<String, Object> values = ColumnValues.coerce("location distance", COLUMNS, body, false);
        if (values.isEmpty()) {
            throw new InvalidRequestException("Nothing to change: provide at least one field");
        }
        normalise(values);

        // The rules span several fields (the two ends against each other), so check the row as it WILL look.
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("fromDestinationId", existing.from().id());
        merged.put("toDestinationId", existing.to().id());
        merged.put("routeType", existing.routeType());
        merged.put("distanceKm", existing.distanceKm());
        merged.put("durationMinutes", existing.durationMinutes());
        merged.putAll(values);
        validate(merged);
        // A pair (either way round) may only be re-checked when it actually moves: legacy data already holds some
        // pairs in both directions, and editing the distance of one of them must still be possible.
        if (values.containsKey("fromDestinationId") || values.containsKey("toDestinationId") || values.containsKey("routeType")) {
            checkNotDuplicate(merged, id);
        }

        List<String> assignments = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            assignments.add(column(json).db() + " = :" + json);
            params.put(json, value);
        });
        params.put("rowId", id);
        jdbc.sql("update location_distance set " + String.join(", ", assignments) + " where id = :rowId").params(params).update();
        return get(id);
    }

    @Override
    @Transactional
    public void delete(int id) {
        int deleted = jdbc.sql("delete from location_distance where id = :id").param("id", id).update();
        if (deleted == 0) {
            throw notFound(id);
        }
    }

    // ------------------------------------------------------------------ validation

    private void normalise(Map<String, Object> values) {
        if (values.get("routeType") instanceof String text) {
            values.put("routeType", text.toUpperCase());
        }
        if (values.get("distanceKm") instanceof BigDecimal distance && distance.stripTrailingZeros().scale() <= 1) {
            values.put("distanceKm", distance.setScale(1));
        }
    }

    // `values` is a complete distance (create) or the merged row (update), keyed by JSON field name.
    private void validate(Map<String, Object> values) {
        Map<String, String> errors = new LinkedHashMap<>();

        Integer from = (Integer) values.get("fromDestinationId");
        Integer to = (Integer) values.get("toDestinationId");
        if (from != null && !destinationExists(from)) {
            errors.put("fromDestinationId", "is not a known destination");
        }
        if (to != null && !destinationExists(to)) {
            errors.put("toDestinationId", "is not a known destination");
        }
        if (from != null && from.equals(to)) {
            errors.put("toDestinationId", "must be a different destination from fromDestinationId");
        }
        if (values.get("routeType") instanceof String routeType && !ROUTE_TYPE.matcher(routeType).matches()) {
            errors.put("routeType", "must be 1-30 letters, digits or underscores, e.g. ROAD, ROADA, ROADB");
        }
        if (values.get("distanceKm") instanceof BigDecimal distance) {
            if (distance.signum() <= 0) {
                errors.put("distanceKm", "must be above zero");
            } else if (distance.compareTo(MAX_DISTANCE_KM) > 0) {
                errors.put("distanceKm", "must not exceed " + MAX_DISTANCE_KM.toPlainString());
            } else if (distance.stripTrailingZeros().scale() > 1) {
                errors.put("distanceKm", "must have at most 1 decimal place");
            }
        }
        if (values.get("durationMinutes") instanceof Integer minutes && (minutes < 1 || minutes > MAX_DURATION_MINUTES)) {
            errors.put("durationMinutes", "must be between 1 and " + MAX_DURATION_MINUTES);
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }
    }

    // The same two destinations with the same route type, in EITHER direction, is the same road: a second row would
    // only create a contradiction.
    private void checkNotDuplicate(Map<String, Object> values, Integer selfId) {
        List<Integer> clash = jdbc.sql("""
                        select id from location_distance
                         where route_type = :routeType and id <> :self
                           and ((from_destination_id = :from and to_destination_id = :to)
                             or (from_destination_id = :to and to_destination_id = :from))
                         order by id""")
                .param("routeType", values.get("routeType")).param("self", selfId == null ? -1 : selfId)
                .param("from", values.get("fromDestinationId")).param("to", values.get("toDestinationId"))
                .query(Integer.class).list();
        if (!clash.isEmpty()) {
            throw new ConflictException("DUPLICATE", "These destinations already have a " + values.get("routeType")
                    + " route (id " + clash.get(0) + "), in one direction or the other; edit that one instead");
        }
    }

    private boolean destinationExists(int id) {
        return jdbc.sql("select count(*) from destination where id = :id").param("id", id).query(Long.class).single() > 0;
    }

    // ------------------------------------------------------------------ helpers

    private LocationDistanceResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        int from = rs.getInt("from_destination_id");
        int to = rs.getInt("to_destination_id");
        BigDecimal distance = rs.getBigDecimal("distance_km");
        return new LocationDistanceResponse(rs.getInt("id"),
                new RefResponse(from, rs.getString("from_name")), new RefResponse(to, rs.getString("to_name")),
                rs.getString("route_type"), distance, rs.getObject("duration_minutes", Integer.class),
                from == to || distance.signum() <= 0);
    }

    private String orderBy(List<String> sort) {
        List<String> parts = new ArrayList<>();
        if (sort != null) {
            for (String entry : sort) {
                String[] bits = entry.split(",");
                String sql = SORTS.get(bits[0].trim());
                String direction = bits.length > 1 ? bits[1].trim().toLowerCase() : "asc";
                if (sql == null || bits.length > 2 || !(direction.equals("asc") || direction.equals("desc"))) {
                    throw InvalidRequestException.field("sort",
                            "'" + entry + "' is not valid; use field,asc|desc with field one of " + SORTS.keySet());
                }
                parts.add(sql + " " + direction);
            }
        }
        if (parts.isEmpty()) {
            parts.add("f.name asc");
            parts.add("t.name asc");
            parts.add("l.route_type asc");
        }
        parts.add("l.id asc");
        return String.join(", ", parts);
    }

    private Column column(String json) {
        return COLUMNS.stream().filter(c -> c.json().equals(json)).findFirst().orElseThrow();
    }

    private NotFoundException notFound(int id) {
        return new NotFoundException("No location distance found with id '" + id + "'");
    }
}
