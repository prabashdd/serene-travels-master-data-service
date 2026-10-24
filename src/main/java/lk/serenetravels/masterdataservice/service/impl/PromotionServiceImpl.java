package lk.serenetravels.masterdataservice.service.impl;

import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.PromotionResponse;
import lk.serenetravels.masterdataservice.dto.RateAmount;
import lk.serenetravels.masterdataservice.dto.RateUnit;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.PromotionService;
import lk.serenetravels.masterdataservice.util.ColumnValues;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Column;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Type;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PromotionServiceImpl implements PromotionService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_GROUP_SIZE = 32767;     // smallint
    public static final String PERCENTAGE = "PERCENTAGE";
    // Mirrors the CHECK constraint on promotion.type.
    private static final List<String> TYPES = List.of(PERCENTAGE, "FIXED_AMOUNT", "COMPLIMENTARY");

    private static final List<Column> COLUMNS = List.of(
            new Column("name", "name", Type.TEXT, true),
            new Column("type", "type", Type.TEXT, true),
            new Column("validFrom", "valid_from", Type.DATE, true),
            new Column("validTo", "valid_to", Type.DATE, true),
            new Column("minGroupSize", "min_group_size", Type.INT, false),
            new Column("destinationId", "destination_id", Type.INT, false),
            new Column("seasonId", "season_id", Type.INT, false),
            new Column("active", "active", Type.BOOL, false));

    private static final Map<String, String> SORTS = Map.of("name", "p.name", "type", "p.type",
            "validFrom", "p.valid_from", "validTo", "p.valid_to", "id", "p.id");

    private static final String SELECT = """
            select p.id, p.name, p.type, p.valid_from, p.valid_to, p.min_group_size, p.destination_id,
                   d.name as destination_name, p.season_id, s.name as season_name, p.active
              from promotion p
              left join destination d on d.id = p.destination_id
              left join season s on s.id = p.season_id""";

    private final JdbcClient jdbc;
    private final Clock clock;

    public PromotionServiceImpl(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public PageResponse<PromotionResponse> list(String q, String type, Boolean active, LocalDate validOn,
                                                Integer destinationId, Integer seasonId, LocalDate at,
                                                List<String> sort, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        String typeFilter = type == null ? null : type.trim().toUpperCase();
        if (typeFilter != null && !TYPES.contains(typeFilter)) {
            throw InvalidRequestException.field("type", "must be one of " + TYPES);
        }
        Map<String, Object> params = new HashMap<>();
        List<String> where = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where.add("lower(p.name) like :q escape '\\'");
            params.put("q", "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (typeFilter != null) {
            where.add("p.type = :type");
            params.put("type", typeFilter);
        }
        if (active != null) {
            where.add("p.active = :active");
            params.put("active", active);
        }
        if (validOn != null) {
            where.add("p.valid_from <= :validOn and p.valid_to > :validOn");
            params.put("validOn", validOn);
        }
        if (destinationId != null) {
            where.add("p.destination_id = :destinationId");
            params.put("destinationId", destinationId);
        }
        if (seasonId != null) {
            where.add("p.season_id = :seasonId");
            params.put("seasonId", seasonId);
        }
        String whereSql = where.isEmpty() ? "" : " where " + String.join(" and ", where);

        Long total = jdbc.sql("select count(*) from promotion p" + whereSql).params(params).query(Long.class).single();

        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<PromotionResponse> rows = jdbc.sql(SELECT + whereSql + " order by " + orderBy(sort) + " limit :limit offset :offset")
                .params(params).query((rs, n) -> map(rs)).list();
        return PageResponse.of(withDetails(rows, at), total, page, size);
    }

    @Override
    public PromotionResponse get(int id, LocalDate at) {
        PromotionResponse row = jdbc.sql(SELECT + " where p.id = :id").param("id", id).query((rs, n) -> map(rs))
                .optional().orElseThrow(() -> notFound(id));
        return withDetails(List.of(row), at).get(0);
    }

    // ------------------------------------------------------------------ writes

    @Override
    @Transactional
    public PromotionResponse create(Map<String, Object> body) {
        Map<String, Object> values = ColumnValues.coerce("promotion", COLUMNS, body, true);
        normalise(values);
        values.putIfAbsent("active", Boolean.TRUE);
        validate(values);

        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            columns.add(column(json).db());
            placeholders.add(":" + json);
            params.put(json, value);
        });
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("insert into promotion (" + String.join(", ", columns) + ") values (" + String.join(", ", placeholders) + ")")
                .params(params).update(keyHolder, "id");
        return get(keyHolder.getKey().intValue(), null);
    }

    @Override
    @Transactional
    public PromotionResponse update(int id, Map<String, Object> body) {
        PromotionResponse existing = get(id, null);
        Map<String, Object> values = ColumnValues.coerce("promotion", COLUMNS, body, false);
        if (values.isEmpty()) {
            throw new InvalidRequestException("Nothing to change: provide at least one field");
        }
        normalise(values);

        // The rules span several fields (dates against each other), so check the row as it WILL look.
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("name", existing.name());
        merged.put("type", existing.type());
        merged.put("validFrom", existing.validFrom());
        merged.put("validTo", existing.validTo());
        merged.put("minGroupSize", existing.minGroupSize());
        merged.put("destinationId", existing.destination() == null ? null : existing.destination().id());
        merged.put("seasonId", existing.season() == null ? null : existing.season().id());
        merged.put("active", existing.active());
        merged.putAll(values);
        validate(merged);

        if (values.containsKey("type") && !values.get("type").equals(existing.type())) {
            Long priced = jdbc.sql("select count(*) from promotion_value where promotion_id = :id").param("id", id)
                    .query(Long.class).single();
            if (priced > 0) {
                throw new ConflictException("TYPE_LOCKED",
                        "The type cannot change once a value has been set, because the value would change meaning; "
                                + "create a new promotion instead");
            }
        }

        List<String> assignments = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            assignments.add(column(json).db() + " = :" + json);
            params.put(json, value);
        });
        params.put("rowId", id);
        jdbc.sql("update promotion set " + String.join(", ", assignments) + " where id = :rowId").params(params).update();
        return get(id, null);
    }

    @Override
    @Transactional
    public void deactivate(int id) {
        int updated = jdbc.sql("update promotion set active = false where id = :id").param("id", id).update();
        if (updated == 0) {
            throw notFound(id);
        }
    }

    // ------------------------------------------------------------------ validation

    private void normalise(Map<String, Object> values) {
        if (values.get("type") instanceof String text) {
            values.put("type", text.toUpperCase());
        }
    }

    // `values` is a complete promotion (create) or the merged row (update), keyed by JSON field name.
    private void validate(Map<String, Object> values) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (values.get("active") == null) {
            errors.put("active", "must be true or false");
        }
        if (!TYPES.contains(values.get("type"))) {
            errors.put("type", "must be one of " + TYPES);
        }
        LocalDate from = (LocalDate) values.get("validFrom");
        LocalDate to = (LocalDate) values.get("validTo");
        if (from != null && to != null && !to.isAfter(from)) {
            errors.put("validTo", "must be after validFrom (the promotion runs up to, not including, this date)");
        }
        if (values.get("minGroupSize") instanceof Integer size && (size < 1 || size > MAX_GROUP_SIZE)) {
            errors.put("minGroupSize", "must be between 1 and " + MAX_GROUP_SIZE);
        }
        if (values.get("destinationId") instanceof Integer destinationId && !exists("destination", destinationId)) {
            errors.put("destinationId", "is not a known destination");
        }
        if (values.get("seasonId") instanceof Integer seasonId && !exists("season", seasonId)) {
            errors.put("seasonId", "is not a known season");
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }
    }

    // `table` is always a literal from this class, never request input.
    private boolean exists(String table, int id) {
        return jdbc.sql("select count(*) from " + table + " where id = :id").param("id", id).query(Long.class).single() > 0;
    }

    // ------------------------------------------------------------------ helpers

    // One query for the whole page: the value in force on the day for each promotion, then status and unit per row.
    private List<PromotionResponse> withDetails(List<PromotionResponse> rows, LocalDate at) {
        if (rows.isEmpty()) {
            return rows;
        }
        LocalDate day = at == null ? LocalDate.now(clock) : at;
        Map<Integer, BigDecimal> valueById = new HashMap<>();
        jdbc.sql("""
                        select promotion_id, "value" as amount from promotion_value
                         where promotion_id in (:ids) and effective_from <= :day
                           and (effective_to is null or effective_to > :day)""")
                .param("ids", rows.stream().map(PromotionResponse::id).toList()).param("day", day)
                .query((rs, n) -> {
                    valueById.put(rs.getInt("promotion_id"), rs.getBigDecimal("amount"));
                    return null;
                }).list();
        return rows.stream().map(r -> new PromotionResponse(r.id(), r.name(), r.type(), r.validFrom(), r.validTo(),
                r.minGroupSize(), r.destination(), r.season(), r.active(), status(r, day),
                valueById.containsKey(r.id())
                        ? new RateAmount(valueById.get(r.id()), PERCENTAGE.equals(r.type()) ? RateUnit.PERCENT : RateUnit.USD)
                        : null)).toList();
    }

    private static String status(PromotionResponse promotion, LocalDate day) {
        if (!promotion.active()) {
            return "INACTIVE";
        }
        if (day.isBefore(promotion.validFrom())) {
            return "UPCOMING";
        }
        return day.isBefore(promotion.validTo()) ? "RUNNING" : "ENDED";
    }

    private PromotionResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Integer destinationId = rs.getObject("destination_id", Integer.class);
        Integer seasonId = rs.getObject("season_id", Integer.class);
        return new PromotionResponse(rs.getInt("id"), rs.getString("name"), rs.getString("type"),
                rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class),
                rs.getObject("min_group_size", Integer.class),
                destinationId == null ? null : new RefResponse(destinationId, rs.getString("destination_name")),
                seasonId == null ? null : new RefResponse(seasonId, rs.getString("season_name")),
                rs.getBoolean("active"), null, null);
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
            parts.add("p.valid_from desc");      // newest first
            parts.add("p.name asc");
        }
        parts.add("p.id asc");
        return String.join(", ", parts);
    }

    private Column column(String json) {
        return COLUMNS.stream().filter(c -> c.json().equals(json)).findFirst().orElseThrow();
    }

    private NotFoundException notFound(int id) {
        return new NotFoundException("No promotion found with id '" + id + "'");
    }
}
