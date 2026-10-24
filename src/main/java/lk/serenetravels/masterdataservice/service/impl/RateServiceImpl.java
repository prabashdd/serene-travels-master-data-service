package lk.serenetravels.masterdataservice.service.impl;

import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RateAdjustItem;
import lk.serenetravels.masterdataservice.dto.RateAdjustRequest;
import lk.serenetravels.masterdataservice.dto.RateCorrectionRequest;
import lk.serenetravels.masterdataservice.dto.RateFamilyResponse;
import lk.serenetravels.masterdataservice.dto.RateKeyResponse;
import lk.serenetravels.masterdataservice.dto.RateRowResponse;
import lk.serenetravels.masterdataservice.dto.RateUnit;
import lk.serenetravels.masterdataservice.dto.RateWriteRequest;
import lk.serenetravels.masterdataservice.dto.ResolvedRateResponse;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.RateService;
import lk.serenetravels.masterdataservice.util.Coerce;
import lk.serenetravels.masterdataservice.util.RateFamilies;
import lk.serenetravels.masterdataservice.util.RateFamilies.Family;
import lk.serenetravels.masterdataservice.util.RateFamilies.Key;
import lk.serenetravels.masterdataservice.util.RateFamilies.KeyType;
import lk.serenetravels.masterdataservice.util.RateMath;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// One engine for all ten price tables. Every family is "append-only history rows" with half-open
// validity [effective_from, effective_to) and at most one open row per key combination, so a rate
// change always closes the open row and inserts a new one (never edits history in place).
@Service
public class RateServiceImpl implements RateService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final BigDecimal PERCENT_CAP = new BigDecimal("100.00");

    private final JdbcClient jdbc;
    private final Clock clock;

    public RateServiceImpl(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ queries

    @Override
    public List<RateFamilyResponse> families() {
        return RateFamilies.all().stream()
                .map(f -> new RateFamilyResponse(f.name(), f.label(), f.unit(), f.perKm(),
                        f.keys().stream()
                                .map(k -> new RateKeyResponse(k.name(), k.label(), k.lookup(),
                                        // The fan-out key is optional on write (omit = all values).
                                        k.required() && !k.name().equals(f.fanOutKey()), k.nullMeansAll(),
                                        k.allowedValues()))
                                .toList()))
                .toList();
    }

    @Override
    public PageResponse<RateRowResponse> list(String familyName, Map<String, String> filter, LocalDate at,
                                              boolean history, int page, int size) {
        Family family = RateFamilies.require(familyName);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        LocalDate on = at != null ? at : today();

        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>(keyConditions(family, parseFilter(family, filter), params));
        if (!history) {
            conditions.add(currentCondition(params, on));
        }
        String where = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);

        Long total = jdbc.sql("select count(*) from " + family.table() + where).params(params)
                .query(Long.class).single();

        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<RawRate> rows = jdbc.sql("select " + selectColumns(family) + " from " + family.table() + where
                        + " order by effective_from desc, id desc limit :limit offset :offset")
                .params(params).query(rowMapper(family)).list();

        return PageResponse.of(toResponses(family, rows, on), total, page, size);
    }

    @Override
    public List<RateRowResponse> listCurrent(String familyName, Map<String, String> filter, LocalDate at) {
        Family family = RateFamilies.require(familyName);
        LocalDate on = at != null ? at : today();

        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>(keyConditions(family, parseFilter(family, filter), params));
        conditions.add(currentCondition(params, on));
        List<RawRate> rows = jdbc.sql("select " + selectColumns(family) + " from " + family.table()
                        + " where " + String.join(" and ", conditions) + " order by id")
                .params(params).query(rowMapper(family)).list();
        return toResponses(family, rows, on);
    }

    @Override
    public List<RateRowResponse> endingWithoutSuccessor(String familyName, LocalDate from, LocalDate to) {
        Family family = RateFamilies.require(familyName);
        // "Same keys" for the successor test; an "applies to all" key matches NULL to NULL. Identifiers come from the registry.
        String sameKeys = family.keys().stream()
                .map(k -> k.nullMeansAll()
                        ? "(u." + k.column() + " = t." + k.column() + " or (u." + k.column() + " is null and t."
                        + k.column() + " is null))"
                        : "u." + k.column() + " = t." + k.column())
                .collect(Collectors.joining(" and "));
        if (sameKeys.isEmpty()) {
            sameKeys = "1 = 1";
        }
        List<Integer> ids = jdbc.sql("select t.id from " + family.table() + " t"
                        + " where t.effective_from <= :today and t.effective_to is not null"
                        + " and t.effective_to > :today and t.effective_to >= :from and t.effective_to <= :to"
                        + " and not exists (select 1 from " + family.table() + " u where u.id <> t.id and " + sameKeys
                        + " and u.effective_from >= t.effective_to)"
                        + " order by t.effective_to, t.id")
                .param("today", today()).param("from", from).param("to", to).query(Integer.class).list();
        return findByIds(family, ids, today());
    }

    @Override
    public ResolvedRateResponse resolve(String familyName, Map<String, String> rawKeys, LocalDate at) {
        Family family = RateFamilies.require(familyName);
        LocalDate on = at != null ? at : today();
        Map<String, Object> keys = normalizeKeys(family, rawKeys, false);

        // Exact key first; then, for each "applies to all" key that was given a value, the row where it is NULL.
        List<Attempt> attempts = new ArrayList<>();
        attempts.add(new Attempt(keys, "EXACT"));
        for (Key key : family.keys()) {
            if (key.nullMeansAll() && keys.get(key.name()) != null) {
                Map<String, Object> fallback = new LinkedHashMap<>(keys);
                fallback.put(key.name(), null);
                attempts.add(new Attempt(fallback, "FALLBACK_" + key.allLabel()));
            }
        }

        for (Attempt attempt : attempts) {
            Map<String, Object> params = new HashMap<>();
            List<String> conditions = new ArrayList<>(keyConditions(family, attempt.keys(), params));
            conditions.add(currentCondition(params, on));
            List<RawRate> found = jdbc.sql("select " + selectColumns(family) + " from " + family.table()
                            + " where " + String.join(" and ", conditions)
                            + " order by effective_from desc, id desc limit 1")
                    .params(params).query(rowMapper(family)).list();
            if (!found.isEmpty()) {
                RateRowResponse row = toResponses(family, found, on).get(0);
                return new ResolvedRateResponse(row.rate(), row.unit(), attempt.matched(), row);
            }
        }
        throw new NotFoundException("NO_RATE", "No " + family.name() + " rate applies on " + on + " for " + keys);
    }

    // ------------------------------------------------------------------ writes

    @Override
    @Transactional
    public List<RateRowResponse> setRate(String familyName, RateWriteRequest request, String createdBy) {
        Family family = RateFamilies.require(familyName);
        List<Integer> ids = setOne(family, request, createdBy);
        return findByIds(family, ids, today());
    }

    @Override
    @Transactional
    public List<RateRowResponse> saveGrid(int propertyId, List<RateWriteRequest> items, String createdBy) {
        Family family = RateFamilies.require("property");
        Long exists = jdbc.sql("select count(*) from property where id = :id").param("id", propertyId)
                .query(Long.class).single();
        if (exists == 0) {
            throw new NotFoundException("No property found with id '" + propertyId + "'");
        }

        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            RateWriteRequest item = items.get(i);
            if (item.keys() == null) {
                throw InvalidRequestException.field("[" + i + "].keys", "keys are required");
            }
            Object given = item.keys().get("propertyId");
            if (given != null && !Coerce.toInt(given, "[" + i + "].keys.propertyId").equals(propertyId)) {
                throw InvalidRequestException.field("[" + i + "].keys.propertyId",
                        "must match the property in the URL (" + propertyId + ")");
            }
            Map<String, Object> keys = new LinkedHashMap<>(item.keys());
            keys.put("propertyId", propertyId);
            ids.addAll(setOne(family, new RateWriteRequest(keys, item.rate(), item.effectiveFrom()), createdBy));
        }
        return findByIds(family, ids, today());
    }

    @Override
    @Transactional
    public RateRowResponse correct(String familyName, int id, RateCorrectionRequest request) {
        Family family = RateFamilies.require(familyName);
        if (request.rate() == null && request.effectiveFrom() == null && request.effectiveTo() == null) {
            throw new InvalidRequestException("Nothing to change: provide rate, effectiveFrom or effectiveTo");
        }
        RawRate current = findRaw(family, id);

        BigDecimal value = request.rate() != null ? validateValue(family, request.rate(), "rate") : current.value();
        if (request.rate() != null) {
            checkCap(family, current.keys(), value, "rate");
        }
        LocalDate from = request.effectiveFrom() != null ? request.effectiveFrom() : current.from();
        LocalDate to = request.effectiveTo() != null ? request.effectiveTo() : current.to();
        if (to != null && !to.isAfter(from)) {
            throw InvalidRequestException.field("effectiveTo", "must be after effectiveFrom");
        }

        jdbc.sql("update " + family.table() + " set " + family.valueColumn()
                        + " = :value, effective_from = :from, effective_to = :to where id = :id")
                .param("value", value).param("from", from).param("to", to).param("id", id).update();
        return findByIds(family, List.of(id), today()).get(0);
    }

    @Override
    @Transactional
    public void deletePending(String familyName, int id) {
        Family family = RateFamilies.require(familyName);
        RawRate row = findRaw(family, id);
        if (!row.from().isAfter(today())) {
            throw new ConflictException("RATE_NOT_PENDING",
                    "Only a rate that has not started yet can be deleted; rate history is kept");
        }

        jdbc.sql("delete from " + family.table() + " where id = :id").param("id", id).update();

        // The row this one replaced was closed at its start date; with it gone, that row stays open.
        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>(keyConditions(family, row.keys(), params));
        conditions.add("effective_to = :reopenFrom");
        params.put("reopenFrom", row.from());
        jdbc.sql("update " + family.table() + " set effective_to = null where " + String.join(" and ", conditions))
                .params(params).update();
    }

    @Override
    @Transactional
    public List<RateAdjustItem> adjust(String familyName, RateAdjustRequest request, String createdBy) {
        Family family = RateFamilies.require(familyName);
        RateAdjustRequest.Rounding rounding = request.rounding() != null
                ? request.rounding() : RateAdjustRequest.Rounding.NEAREST_0_01;
        LocalDate from = request.effectiveFrom() != null ? request.effectiveFrom() : today();

        Map<String, List<Object>> filter = parseAdjustFilter(family, request.filter());
        Map<String, Object> params = new HashMap<>();
        List<String> conditions = new ArrayList<>();
        filter.forEach((name, values) -> {
            conditions.add(family.key(name).column() + " in (:f_" + name + ")");
            params.put("f_" + name, values);
        });
        LocalDate today = today();
        conditions.add(currentCondition(params, today));

        List<RawRate> rows = jdbc.sql("select " + selectColumns(family) + " from " + family.table()
                        + " where " + String.join(" and ", conditions) + " order by id")
                .params(params).query(rowMapper(family)).list();

        List<RateAdjustItem> items = new ArrayList<>();
        for (RawRate row : rows) {
            BigDecimal newRate = RateMath.adjust(row.value(), request.mode(), request.value(), rounding);
            RateUnit unit = unitFor(family, row.keys());
            BigDecimal max = unit == RateUnit.PERCENT && family.max().compareTo(PERCENT_CAP) > 0 ? PERCENT_CAP : family.max();
            if (newRate.signum() < 0 || newRate.compareTo(max) > 0) {
                throw InvalidRequestException.field("value",
                        "would produce an out-of-range rate (" + newRate + ") for " + row.keys());
            }
            items.add(new RateAdjustItem(row.keys(), row.value(), newRate, unit));
        }

        if (!request.dryRun()) {
            for (RateAdjustItem item : items) {
                if (item.newRate().compareTo(item.oldRate()) != 0) {
                    applyOne(family, item.keys(), item.newRate(), from, createdBy);
                }
            }
        }
        return items;
    }

    // ------------------------------------------------------------------ core rule

    private List<Integer> setOne(Family family, RateWriteRequest request, String createdBy) {
        Map<String, Object> keys = normalizeKeys(family, request.keys(), true);
        BigDecimal value = validateValue(family, request.rate(), "rate");
        checkCap(family, keys, value, "rate");
        LocalDate from = request.effectiveFrom() != null ? request.effectiveFrom() : today();

        List<Integer> ids = new ArrayList<>();
        for (Map<String, Object> combination : expandFanOut(family, keys)) {
            ids.add(applyOne(family, combination, value, from, createdBy));
        }
        return ids;
    }

    // A flat vehicle rate is stored once per season; an omitted seasonId therefore means "every season".
    private List<Map<String, Object>> expandFanOut(Family family, Map<String, Object> keys) {
        if (family.fanOutKey() == null || keys.containsKey(family.fanOutKey())) {
            return List.of(keys);
        }
        List<Integer> values = jdbc.sql(family.fanOutSql()).query(Integer.class).list();
        if (values.isEmpty()) {
            throw new InvalidRequestException("No " + family.fanOutKey() + " values exist to apply the rate to");
        }
        return values.stream().map(v -> {
            Map<String, Object> combination = new LinkedHashMap<>(keys);
            combination.put(family.fanOutKey(), v);
            return combination;
        }).toList();
    }

    private Integer applyOne(Family family, Map<String, Object> keys, BigDecimal value, LocalDate from,
                             String createdBy) {
        LocalDate today = today();
        Map<String, Object> params = new HashMap<>();
        String keyWhere = String.join(" and ", keyConditions(family, keys, params));
        if (keyWhere.isEmpty()) {
            keyWhere = "1 = 1";
        }

        List<OpenRow> open = jdbc.sql("select id, effective_from from " + family.table() + " where " + keyWhere
                        + " and effective_to is null for update")
                .params(params)
                .query((rs, n) -> new OpenRow(rs.getInt(1), rs.getObject(2, LocalDate.class))).list();

        if (open.isEmpty()) {
            Map<String, Object> overlapParams = new HashMap<>(params);
            overlapParams.put("newFrom", from);
            Long overlapping = jdbc.sql("select count(*) from " + family.table() + " where " + keyWhere
                    + " and effective_to > :newFrom").params(overlapParams).query(Long.class).single();
            if (overlapping > 0) {
                throw new ConflictException("RATE_STARTS_BEFORE_CURRENT",
                        "A rate already covers " + from + "; use PATCH to correct it");
            }
        } else {
            OpenRow current = open.get(0);
            if (current.from().isAfter(today)) {
                throw new ConflictException("FUTURE_RATE_EXISTS",
                        "A rate starting " + current.from() + " is already pending; edit or delete it first");
            }
            if (!from.isAfter(current.from())) {
                throw new ConflictException("RATE_STARTS_BEFORE_CURRENT",
                        "The current rate started on " + current.from() + "; effectiveFrom must be later "
                                + "(use PATCH to correct a mistake)");
            }
            jdbc.sql("update " + family.table() + " set effective_to = :newFrom where id = :id")
                    .param("newFrom", from).param("id", current.id()).update();
        }

        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        Map<String, Object> insertParams = new HashMap<>();
        for (Key key : family.keys()) {
            columns.add(key.column());
            placeholders.add(":k_" + key.name());
            insertParams.put("k_" + key.name(), keys.get(key.name()));
        }
        columns.addAll(List.of(family.valueColumn(), "effective_from", "created_by"));
        placeholders.addAll(List.of(":value", ":effectiveFrom", ":createdBy"));
        insertParams.put("value", value);
        insertParams.put("effectiveFrom", from);
        insertParams.put("createdBy", createdBy);

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("insert into " + family.table() + " (" + String.join(", ", columns) + ") values ("
                        + String.join(", ", placeholders) + ")")
                .params(insertParams).update(keyHolder, "id");
        return keyHolder.getKey().intValue();
    }

    // ------------------------------------------------------------------ keys & values

    // Validates a key map against the family and returns EVERY family key (absent "applies to all" keys
    // as null). With allowFanOutAbsent the fan-out key may be missing from the result, meaning "all".
    private Map<String, Object> normalizeKeys(Family family, Map<String, ?> raw, boolean allowFanOutAbsent) {
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> known = family.keys().stream().map(Key::name).collect(Collectors.toSet());
        for (String name : raw.keySet()) {
            if (!known.contains(name)) {
                errors.put("keys." + name, "is not a key of the '" + family.name() + "' family");
            }
        }

        Map<String, Object> keys = new LinkedHashMap<>();
        for (Key key : family.keys()) {
            Object value = raw.get(key.name());
            if (value == null) {
                if (key.required()) {
                    if (allowFanOutAbsent && key.name().equals(family.fanOutKey())) {
                        continue;
                    }
                    errors.put("keys." + key.name(), "is required");
                } else {
                    keys.put(key.name(), null);
                }
                continue;
            }
            try {
                keys.put(key.name(), coerceKey(key, value, "keys." + key.name()));
            } catch (InvalidRequestException ex) {
                errors.putAll(ex.getFieldErrors());
            }
        }

        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }
        return keys;
    }

    private Object coerceKey(Key key, Object value, String field) {
        if (key.type() == KeyType.INT) {
            return Coerce.toInt(value, field);
        }
        String text = Coerce.toText(value, field);
        if (key.allowedValues() != null && !key.allowedValues().contains(text)) {
            throw InvalidRequestException.field(field, "must be one of " + key.allowedValues());
        }
        return text;
    }

    // Query-string filter (filter[propertyId]=42). Only the keys given are filtered on; the literal
    // "null" selects the "applies to all" row for a key that allows it.
    private Map<String, Object> parseFilter(Family family, Map<String, String> filter) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : filter.entrySet()) {
            Key key = family.key(entry.getKey());
            String field = "filter." + entry.getKey();
            if (key == null) {
                errors.put(field, "is not a key of the '" + family.name() + "' family");
            } else if (key.nullMeansAll() && "null".equalsIgnoreCase(entry.getValue())) {
                parsed.put(key.name(), null);
            } else {
                try {
                    parsed.put(key.name(), coerceKey(key, entry.getValue(), field));
                } catch (InvalidRequestException ex) {
                    errors.putAll(ex.getFieldErrors());
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }
        return parsed;
    }

    private Map<String, List<Object>> parseAdjustFilter(Family family, Map<String, Object> filter) {
        Map<String, List<Object>> parsed = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            Key key = family.key(entry.getKey());
            String field = "filter." + entry.getKey();
            if (key == null) {
                errors.put(field, "is not a key of the '" + family.name() + "' family");
                continue;
            }
            List<?> values = entry.getValue() instanceof List<?> list ? list : List.of(entry.getValue());
            if (values.isEmpty()) {
                errors.put(field, "must not be an empty list");
                continue;
            }
            try {
                parsed.put(key.name(), values.stream().map(v -> coerceKey(key, v, field)).toList());
            } catch (InvalidRequestException ex) {
                errors.putAll(ex.getFieldErrors());
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }
        return parsed;
    }

    private BigDecimal validateValue(Family family, BigDecimal value, String field) {
        if (value == null) {
            throw InvalidRequestException.field(field, "is required");
        }
        if (value.signum() < 0) {
            throw InvalidRequestException.field(field, "must not be negative");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw InvalidRequestException.field(field, "must have at most 2 decimal places");
        }
        if (value.compareTo(family.max()) > 0) {
            throw InvalidRequestException.field(field, "must not exceed " + family.max().toPlainString());
        }
        return value.setScale(2);
    }

    // The unit of one rate row. It is the family's, except for promotions, whose value is a percentage or an
    // amount of money depending on the promotion's own type (COMPLIMENTARY is treated as an amount).
    private RateUnit unitFor(Family family, Map<String, Object> keys) {
        if (isPromotion(family) && keys.get("promotionId") instanceof Integer id) {
            return promotionUnits(List.of(id)).getOrDefault(id, family.unit());
        }
        return family.unit();
    }

    // A percentage can never exceed 100, whatever the family's money ceiling is.
    private void checkCap(Family family, Map<String, Object> keys, BigDecimal value, String field) {
        if (unitFor(family, keys) == RateUnit.PERCENT && value.compareTo(PERCENT_CAP) > 0) {
            throw InvalidRequestException.field(field, "must not exceed 100 for a percentage");
        }
    }

    private Map<Integer, RateUnit> promotionUnits(Collection<Integer> ids) {
        Map<Integer, RateUnit> units = new HashMap<>();
        if (ids.isEmpty()) {
            return units;
        }
        jdbc.sql("select id, type from promotion where id in (:ids)").param("ids", ids)
                .query((rs, n) -> {
                    units.put(rs.getInt("id"), "PERCENTAGE".equals(rs.getString("type")) ? RateUnit.PERCENT : RateUnit.USD);
                    return null;
                }).list();
        return units;
    }

    private static boolean isPromotion(Family family) {
        return "promotion".equals(family.name());
    }

    // ------------------------------------------------------------------ SQL helpers

    // `column = :k_name` (or `column is null`) per key present in the map. Identifiers come from the
    // family registry; values are always bound parameters.
    private List<String> keyConditions(Family family, Map<String, Object> keys, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();
        for (Key key : family.keys()) {
            if (!keys.containsKey(key.name())) {
                continue;
            }
            Object value = keys.get(key.name());
            if (value == null) {
                conditions.add(key.column() + " is null");
            } else {
                conditions.add(key.column() + " = :k_" + key.name());
                params.put("k_" + key.name(), value);
            }
        }
        return conditions;
    }

    private String currentCondition(Map<String, Object> params, LocalDate on) {
        params.put("asOf", on);
        return "effective_from <= :asOf and (effective_to is null or effective_to > :asOf)";
    }

    private String selectColumns(Family family) {
        String keyColumns = family.keys().stream().map(Key::column).collect(Collectors.joining(", "));
        return "id, " + (keyColumns.isEmpty() ? "" : keyColumns + ", ") + family.valueColumn()
                + " as rate_value, effective_from, effective_to, created_at, created_by";
    }

    private RowMapper<RawRate> rowMapper(Family family) {
        return (rs, rowNum) -> {
            Map<String, Object> keys = new LinkedHashMap<>();
            for (Key key : family.keys()) {
                keys.put(key.name(), key.type() == KeyType.INT
                        ? rs.getObject(key.column(), Integer.class) : rs.getString(key.column()));
            }
            OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
            return new RawRate(rs.getInt("id"), keys, rs.getBigDecimal("rate_value"),
                    rs.getObject("effective_from", LocalDate.class), rs.getObject("effective_to", LocalDate.class),
                    createdAt == null ? null : createdAt.toInstant(), rs.getString("created_by"));
        };
    }

    private RawRate findRaw(Family family, int id) {
        return jdbc.sql("select " + selectColumns(family) + " from " + family.table() + " where id = :id")
                .param("id", id).query(rowMapper(family)).optional()
                .orElseThrow(() -> new NotFoundException("RATE_NOT_FOUND",
                        "No " + family.name() + " rate found with id '" + id + "'"));
    }

    private List<RateRowResponse> findByIds(Family family, List<Integer> ids, LocalDate on) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<RawRate> rows = jdbc.sql("select " + selectColumns(family) + " from " + family.table()
                        + " where id in (:ids) order by id")
                .param("ids", ids).query(rowMapper(family)).list();
        return toResponses(family, rows, on);
    }

    // Display names for the key columns are fetched in one query per key for the whole result set
    // (not per row), so a big grid costs a handful of queries.
    private List<RateRowResponse> toResponses(Family family, List<RawRate> rows, LocalDate on) {
        Map<String, Map<Integer, String>> names = new HashMap<>();
        for (Key key : family.keys()) {
            if (key.labelSql() == null) {
                continue;
            }
            Set<Integer> ids = new LinkedHashSet<>();
            for (RawRate row : rows) {
                if (row.keys().get(key.name()) instanceof Integer id) {
                    ids.add(id);
                }
            }
            Map<Integer, String> byId = new HashMap<>();
            if (!ids.isEmpty()) {
                jdbc.sql(key.labelSql()).param("ids", ids)
                        .query((rs, n) -> Map.entry(rs.getInt(1), rs.getString(2))).list()
                        .forEach(entry -> byId.put(entry.getKey(), entry.getValue()));
            }
            names.put(key.name(), byId);
        }

        Map<Integer, RateUnit> promotionUnits = isPromotion(family)
                ? promotionUnits(rows.stream().map(r -> r.keys().get("promotionId")).filter(Integer.class::isInstance)
                .map(Integer.class::cast).collect(Collectors.toSet()))
                : Map.of();

        return rows.stream().map(row -> {
            Map<String, String> labels = new LinkedHashMap<>();
            for (Key key : family.keys()) {
                if (key.labelSql() != null) {
                    Object id = row.keys().get(key.name());
                    labels.put(key.name(), id == null ? null : names.get(key.name()).get((Integer) id));
                }
            }
            boolean isCurrent = !row.from().isAfter(on) && (row.to() == null || row.to().isAfter(on));
            RateUnit unit = row.keys().get("promotionId") instanceof Integer promotionId
                    ? promotionUnits.getOrDefault(promotionId, family.unit()) : family.unit();
            return new RateRowResponse(row.id(), family.name(), row.keys(), labels, row.value(), unit,
                    row.from(), row.to(), isCurrent, row.createdAt(), row.createdBy());
        }).toList();
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private record RawRate(int id, Map<String, Object> keys, BigDecimal value, LocalDate from, LocalDate to,
                           Instant createdAt, String createdBy) {
    }

    private record OpenRow(int id, LocalDate from) {
    }

    private record Attempt(Map<String, Object> keys, String matched) {
    }
}
