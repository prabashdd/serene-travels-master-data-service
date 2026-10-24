package lk.serenetravels.masterdataservice.service.impl;

import lk.serenetravels.masterdataservice.dto.AttractionRateAmount;
import lk.serenetravels.masterdataservice.dto.AttractionResponse;
import lk.serenetravels.masterdataservice.dto.AttractionSummaryResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RateAmount;
import lk.serenetravels.masterdataservice.dto.RateRowResponse;
import lk.serenetravels.masterdataservice.dto.RateUnit;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.dto.TukTukResponse;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.AttractionService;
import lk.serenetravels.masterdataservice.service.RateService;
import lk.serenetravels.masterdataservice.util.ColumnValues;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Column;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Type;
import lk.serenetravels.masterdataservice.util.SqlErrors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AttractionServiceImpl implements AttractionService {

    private static final int MAX_PAGE_SIZE = 200;
    public static final String PER_PERSON = "PER_PERSON";
    public static final String FIXED_GROUP_PLUS_EXTRA = "FIXED_GROUP_PLUS_EXTRA";
    private static final List<String> PRICING_METHODS = List.of(PER_PERSON, FIXED_GROUP_PLUS_EXTRA);

    private static final List<Column> COLUMNS = List.of(
            new Column("name", "name", Type.TEXT, true),
            new Column("destinationId", "destination_id", Type.INT, true),
            new Column("pricingMethod", "pricing_method", Type.TEXT, true),
            new Column("isSafari", "is_safari", Type.BOOL, false),
            new Column("jeepCapacity", "jeep_capacity", Type.INT, false),
            new Column("fixedGroupSize", "fixed_group_size", Type.INT, false));

    private static final Map<String, String> SORTS = Map.of(
            "name", "a.name", "destination", "d.name", "pricingMethod", "a.pricing_method", "id", "a.id");

    private final JdbcClient jdbc;
    private final Clock clock;
    private final RateService rateService;

    public AttractionServiceImpl(JdbcClient jdbc, Clock clock, RateService rateService) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.rateService = rateService;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public PageResponse<AttractionSummaryResponse> list(String q, Integer destinationId, Boolean safari,
                                                        String pricingMethod, List<String> sort, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        if (pricingMethod != null && !PRICING_METHODS.contains(pricingMethod)) {
            throw InvalidRequestException.field("pricingMethod", "must be one of " + PRICING_METHODS);
        }
        Map<String, Object> params = new HashMap<>();
        List<String> where = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where.add("lower(a.name) like :q escape '\\'");
            params.put("q", "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (destinationId != null) {
            where.add("a.destination_id = :destinationId");
            params.put("destinationId", destinationId);
        }
        if (safari != null) {
            where.add("a.is_safari = :safari");
            params.put("safari", safari);
        }
        if (pricingMethod != null) {
            where.add("a.pricing_method = :pricingMethod");
            params.put("pricingMethod", pricingMethod);
        }
        String whereSql = where.isEmpty() ? "" : " where " + String.join(" and ", where);

        Long total = jdbc.sql("select count(*) from attraction a" + whereSql).params(params).query(Long.class).single();

        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<AttractionSummaryResponse> rows = jdbc.sql("""
                        select a.id, a.name, a.destination_id, d.name as destination_name, a.pricing_method, a.is_safari,
                               a.jeep_capacity, a.fixed_group_size
                          from attraction a join destination d on d.id = a.destination_id""" + whereSql
                        + " order by " + orderBy(sort) + " limit :limit offset :offset")
                .params(params)
                .query((rs, n) -> new AttractionSummaryResponse(rs.getInt("id"), rs.getString("name"),
                        new RefResponse(rs.getInt("destination_id"), rs.getString("destination_name")),
                        rs.getString("pricing_method"), rs.getBoolean("is_safari"),
                        rs.getObject("jeep_capacity", Integer.class), rs.getObject("fixed_group_size", Integer.class),
                        List.of()))
                .list();

        // One query for the whole page, not one per row.
        Map<Integer, List<AttractionRateAmount>> ratesById = currentRatesFor(rows.stream().map(AttractionSummaryResponse::id).toList());
        List<AttractionSummaryResponse> withRates = rows.stream()
                .map(r -> new AttractionSummaryResponse(r.id(), r.name(), r.destination(), r.pricingMethod(), r.isSafari(),
                        r.jeepCapacity(), r.fixedGroupSize(), ratesById.getOrDefault(r.id(), List.of())))
                .toList();
        return PageResponse.of(withRates, total, page, size);
    }

    @Override
    public AttractionResponse get(int id, LocalDate at) {
        AttractionResponse base = jdbc.sql("""
                        select a.id, a.name, a.destination_id, d.name as destination_name, a.pricing_method, a.is_safari,
                               a.jeep_capacity, a.fixed_group_size
                          from attraction a join destination d on d.id = a.destination_id where a.id = :id""")
                .param("id", id)
                .query((rs, n) -> new AttractionResponse(rs.getInt("id"), rs.getString("name"),
                        new RefResponse(rs.getInt("destination_id"), rs.getString("destination_name")),
                        rs.getString("pricing_method"), rs.getBoolean("is_safari"),
                        rs.getObject("jeep_capacity", Integer.class), rs.getObject("fixed_group_size", Integer.class),
                        null, null))
                .optional().orElseThrow(() -> notFound(id));

        List<RateRowResponse> rates = rateService.listCurrent("attraction", Map.of("attractionId", String.valueOf(id)), at);
        return new AttractionResponse(base.id(), base.name(), base.destination(), base.pricingMethod(), base.isSafari(),
                base.jeepCapacity(), base.fixedGroupSize(), rates, tukTuk(id, at));
    }

    // ------------------------------------------------------------------ writes

    @Override
    @Transactional
    public AttractionResponse create(Map<String, Object> body) {
        Map<String, Object> values = ColumnValues.coerce("attraction", COLUMNS, body, true);
        values.putIfAbsent("isSafari", Boolean.FALSE);
        validate(values, null);

        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            columns.add(column(json).db());
            placeholders.add(":" + json);
            params.put(json, value);
        });
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("insert into attraction (" + String.join(", ", columns) + ") values ("
                + String.join(", ", placeholders) + ")").params(params).update(keyHolder, "id");
        return get(keyHolder.getKey().intValue(), null);
    }

    @Override
    @Transactional
    public AttractionResponse update(int id, Map<String, Object> body) {
        Map<String, Object> existing = row(id);
        Map<String, Object> values = ColumnValues.coerce("attraction", COLUMNS, body, false);
        if (values.isEmpty()) {
            throw new InvalidRequestException("Nothing to change: provide at least one field");
        }
        // Rules span several fields (method vs group size), so check the row as it WILL look.
        Map<String, Object> merged = new LinkedHashMap<>(existing);
        merged.putAll(values);
        validate(merged, id);

        List<String> assignments = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            assignments.add(column(json).db() + " = :" + json);
            params.put(json, value);
        });
        params.put("rowId", id);
        jdbc.sql("update attraction set " + String.join(", ", assignments) + " where id = :rowId").params(params).update();
        return get(id, null);
    }

    @Override
    @Transactional
    public void delete(int id) {
        row(id);
        try {
            jdbc.sql("delete from attraction where id = :id").param("id", id).update();
        } catch (DataIntegrityViolationException ex) {
            if (SqlErrors.FOREIGN_KEY_VIOLATION.equals(SqlErrors.sqlState(ex))) {
                throw new ConflictException("IN_USE",
                        "This attraction still has rates, a tuk-tuk option or is part of a package; remove those first");
            }
            throw ex;
        }
    }

    @Override
    @Transactional
    public TukTukResponse enableTukTuk(int attractionId) {
        row(attractionId);
        Integer existing = jdbc.sql("select id from tuk_tuk_location where attraction_id = :id").param("id", attractionId)
                .query(Integer.class).optional().orElse(null);
        if (existing == null) {
            jdbc.sql("insert into tuk_tuk_location (attraction_id) values (:id)").param("id", attractionId).update();
        }
        return tukTuk(attractionId, null);
    }

    @Override
    @Transactional
    public void disableTukTuk(int attractionId) {
        row(attractionId);
        try {
            jdbc.sql("delete from tuk_tuk_location where attraction_id = :id").param("id", attractionId).update();
        } catch (DataIntegrityViolationException ex) {
            if (SqlErrors.FOREIGN_KEY_VIOLATION.equals(SqlErrors.sqlState(ex))) {
                throw new ConflictException("IN_USE", "Tuk-tuk fares exist for this attraction; end or remove them first");
            }
            throw ex;
        }
    }

    // ------------------------------------------------------------------ validation

    // `values` is a complete attraction (create) or the merged row (update), keyed by JSON field name.
    private void validate(Map<String, Object> values, Integer selfId) {
        Map<String, String> errors = new LinkedHashMap<>();
        String method = (String) values.get("pricingMethod");

        if (values.get("isSafari") == null) {
            errors.put("isSafari", "must be true or false");
        }
        if (method != null && !PRICING_METHODS.contains(method)) {
            errors.put("pricingMethod", "must be one of " + PRICING_METHODS);
        }
        if (values.get("jeepCapacity") instanceof Integer capacity && (capacity < 1 || capacity > 50)) {
            errors.put("jeepCapacity", "must be between 1 and 50");
        }
        Integer groupSize = (Integer) values.get("fixedGroupSize");
        if (FIXED_GROUP_PLUS_EXTRA.equals(method)) {
            if (groupSize == null) {
                errors.put("fixedGroupSize", "is required when pricingMethod is " + FIXED_GROUP_PLUS_EXTRA);
            } else if (groupSize < 1 || groupSize > 100) {
                errors.put("fixedGroupSize", "must be between 1 and 100");
            }
        } else if (PER_PERSON.equals(method) && groupSize != null) {
            errors.put("fixedGroupSize", "only applies to " + FIXED_GROUP_PLUS_EXTRA + "; clear it for " + PER_PERSON);
        }
        if (values.get("destinationId") instanceof Integer destinationId) {
            Long found = jdbc.sql("select count(*) from destination where id = :id").param("id", destinationId)
                    .query(Long.class).single();
            if (found == 0) {
                errors.put("destinationId", "is not a known destination");
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }

        // The same name may exist at different destinations, but not twice at one.
        Long clash = jdbc.sql("""
                        select count(*) from attraction
                         where destination_id = :destinationId and lower(name) = :name and id <> :self""")
                .param("destinationId", values.get("destinationId"))
                .param("name", ((String) values.get("name")).toLowerCase())
                .param("self", selfId == null ? -1 : selfId).query(Long.class).single();
        if (clash > 0) {
            throw new ConflictException("DUPLICATE", "This destination already has an attraction named '"
                    + values.get("name") + "'");
        }
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> row(int id) {
        return jdbc.sql("""
                        select name, destination_id, pricing_method, is_safari, jeep_capacity, fixed_group_size
                          from attraction where id = :id""").param("id", id)
                .query((rs, n) -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", rs.getString("name"));
                    map.put("destinationId", rs.getInt("destination_id"));
                    map.put("pricingMethod", rs.getString("pricing_method"));
                    map.put("isSafari", rs.getBoolean("is_safari"));
                    map.put("jeepCapacity", rs.getObject("jeep_capacity", Integer.class));
                    map.put("fixedGroupSize", rs.getObject("fixed_group_size", Integer.class));
                    return map;
                }).optional().orElseThrow(() -> notFound(id));
    }

    private Map<Integer, List<AttractionRateAmount>> currentRatesFor(List<Integer> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        LocalDate today = LocalDate.now(clock);
        return jdbc.sql("""
                        select attraction_id, rate_type, rate from attraction_rate
                         where attraction_id in (:ids) and effective_from <= :today
                           and (effective_to is null or effective_to > :today)
                         order by attraction_id, rate_type""")
                .param("ids", ids).param("today", today)
                .query((rs, n) -> Map.entry(rs.getInt("attraction_id"),
                        new AttractionRateAmount(rs.getString("rate_type"), rs.getBigDecimal("rate"), RateUnit.USD)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    private TukTukResponse tukTuk(int attractionId, LocalDate at) {
        Integer locationId = jdbc.sql("select id from tuk_tuk_location where attraction_id = :id").param("id", attractionId)
                .query(Integer.class).optional().orElse(null);
        if (locationId == null) {
            return null;
        }
        RateAmount fare = rateService.listCurrent("tuk-tuk", Map.of("tukTukLocationId", String.valueOf(locationId)), at)
                .stream().findFirst().map(r -> new RateAmount(r.rate(), r.unit())).orElse(null);
        return new TukTukResponse(locationId, fare);
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
            parts.add("a.name asc");
        }
        parts.add("a.id asc");
        return String.join(", ", parts);
    }

    private Column column(String json) {
        return COLUMNS.stream().filter(c -> c.json().equals(json)).findFirst().orElseThrow();
    }

    private NotFoundException notFound(int id) {
        return new NotFoundException("No attraction found with id '" + id + "'");
    }
}
