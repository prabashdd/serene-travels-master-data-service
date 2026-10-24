package lk.serenetravels.masterdataservice.service.impl;

import lk.serenetravels.masterdataservice.dto.BabyCotRequest;
import lk.serenetravels.masterdataservice.dto.BabyCotResponse;
import lk.serenetravels.masterdataservice.dto.ChildRuleRequest;
import lk.serenetravels.masterdataservice.dto.ChildRuleResponse;
import lk.serenetravels.masterdataservice.dto.DietarySupportRequest;
import lk.serenetravels.masterdataservice.dto.DietarySupportResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.PropertyInclusionRequest;
import lk.serenetravels.masterdataservice.dto.PropertyInclusionResponse;
import lk.serenetravels.masterdataservice.dto.PropertyResponse;
import lk.serenetravels.masterdataservice.dto.PropertySummaryResponse;
import lk.serenetravels.masterdataservice.dto.RateAmount;
import lk.serenetravels.masterdataservice.dto.RateRowResponse;
import lk.serenetravels.masterdataservice.dto.RateUnit;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.dto.RoomTypeResponse;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.PropertyService;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class PropertyServiceImpl implements PropertyService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final BigDecimal MAX_MONEY = new BigDecimal("99999999.99");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final List<Column> PROPERTY_COLUMNS = List.of(
            new Column("code", "code", Type.TEXT, true),
            new Column("name", "name", Type.TEXT, true),
            new Column("destinationId", "destination_id", Type.INT, true),
            new Column("address", "address", Type.TEXT, false),
            new Column("starRating", "star_rating", Type.INT, false),
            new Column("website", "website", Type.TEXT, false),
            new Column("email", "email", Type.TEXT, false),
            new Column("phone", "phone", Type.TEXT, false),
            new Column("active", "active", Type.BOOL, false));

    private static final List<Column> ROOM_TYPE_COLUMNS = List.of(
            new Column("name", "name", Type.TEXT, true),
            new Column("baseCapacity", "base_capacity", Type.INT, false),
            new Column("extraBedAllowed", "extra_bed_allowed", Type.BOOL, false));

    // The only fields a list may be sorted by -> the SQL they map to (never built from request text).
    private static final Map<String, String> SORTS = Map.of(
            "name", "p.name", "code", "p.code", "starRating", "p.star_rating", "id", "p.id", "destination", "d.name");

    private final JdbcClient jdbc;
    private final Clock clock;
    private final RateService rateService;

    public PropertyServiceImpl(JdbcClient jdbc, Clock clock, RateService rateService) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.rateService = rateService;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public PageResponse<PropertySummaryResponse> list(String q, Integer destinationId, Boolean active,
                                                      Integer starRating, List<String> sort, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        Map<String, Object> params = new HashMap<>();
        List<String> where = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where.add("(lower(p.name) like :q escape '\\' or lower(p.code) like :q escape '\\')");
            params.put("q", "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (destinationId != null) {
            where.add("p.destination_id = :destinationId");
            params.put("destinationId", destinationId);
        }
        if (active != null) {
            where.add("p.active = :active");
            params.put("active", active);
        }
        if (starRating != null) {
            where.add("p.star_rating = :starRating");
            params.put("starRating", starRating);
        }
        String whereSql = where.isEmpty() ? "" : " where " + String.join(" and ", where);

        Long total = jdbc.sql("select count(*) from property p" + whereSql).params(params).query(Long.class).single();

        params.put("today", LocalDate.now(clock));
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<PropertySummaryResponse> rows = jdbc.sql("""
                        select p.id, p.code, p.name, p.active, p.star_rating, p.destination_id, d.name as destination_name,
                               (select count(*) from room_type rt where rt.property_id = p.id) as room_types,
                               (select min(pr.rate) from property_rate pr
                                 where pr.property_id = p.id and pr.effective_from <= :today
                                   and (pr.effective_to is null or pr.effective_to > :today)) as from_rate
                          from property p join destination d on d.id = p.destination_id""" + whereSql
                        + " order by " + orderBy(sort) + " limit :limit offset :offset")
                .params(params)
                .query((rs, n) -> new PropertySummaryResponse(rs.getInt("id"), rs.getString("code"), rs.getString("name"),
                        rs.getBoolean("active"), rs.getObject("star_rating", Integer.class),
                        new RefResponse(rs.getInt("destination_id"), rs.getString("destination_name")),
                        rs.getInt("room_types"),
                        rs.getBigDecimal("from_rate") == null ? null
                                : new RateAmount(rs.getBigDecimal("from_rate"), RateUnit.USD)))
                .list();
        return PageResponse.of(rows, total, page, size);
    }

    @Override
    public PropertyResponse get(int id, boolean includeRates, LocalDate at) {
        PropertyResponse base = jdbc.sql("""
                        select p.id, p.code, p.name, p.active, p.star_rating, p.address, p.website, p.email, p.phone,
                               p.destination_id, d.name as destination_name
                          from property p join destination d on d.id = p.destination_id where p.id = :id""")
                .param("id", id)
                .query((rs, n) -> new PropertyResponse(rs.getInt("id"), rs.getString("code"), rs.getString("name"),
                        rs.getBoolean("active"), rs.getObject("star_rating", Integer.class), rs.getString("address"),
                        rs.getString("website"), rs.getString("email"), rs.getString("phone"),
                        new RefResponse(rs.getInt("destination_id"), rs.getString("destination_name")),
                        null, null, null, null, null, null))
                .optional().orElseThrow(() -> propertyNotFound(id));

        List<RoomTypeResponse> roomTypes = jdbc.sql("""
                        select id, name, base_capacity, extra_bed_allowed from room_type
                         where property_id = :id order by name, id""").param("id", id)
                .query((rs, n) -> new RoomTypeResponse(rs.getInt("id"), rs.getString("name"),
                        rs.getObject("base_capacity", Integer.class), rs.getBoolean("extra_bed_allowed"))).list();
        List<ChildRuleResponse> childRules = childRules(id);
        List<PropertyInclusionResponse> inclusions = inclusions(id);
        BabyCotResponse babyCot = jdbc.sql("select available, cost from baby_cot_option where property_id = :id")
                .param("id", id)
                .query((rs, n) -> new BabyCotResponse(rs.getBoolean("available"), rs.getBigDecimal("cost")))
                .optional().orElse(null);
        List<DietarySupportResponse> dietary = dietary(id);
        List<RateRowResponse> rates = includeRates
                ? rateService.listCurrent("property", Map.of("propertyId", String.valueOf(id)), at) : null;

        return new PropertyResponse(base.id(), base.code(), base.name(), base.active(), base.starRating(),
                base.address(), base.website(), base.email(), base.phone(), base.destination(), roomTypes, childRules,
                inclusions, babyCot, dietary, rates);
    }

    // ------------------------------------------------------------------ property writes

    @Override
    @Transactional
    public PropertyResponse create(Map<String, Object> body) {
        Map<String, Object> values = ColumnValues.coerce("property", PROPERTY_COLUMNS, body, true);
        validateProperty(values, null);

        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            columns.add(column(PROPERTY_COLUMNS, json).db());
            placeholders.add(":" + json);
            params.put(json, value);
        });
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("insert into property (" + String.join(", ", columns) + ") values ("
                + String.join(", ", placeholders) + ")").params(params).update(keyHolder, "id");
        return get(keyHolder.getKey().intValue(), false, null);
    }

    @Override
    @Transactional
    public PropertyResponse update(int id, Map<String, Object> body) {
        requireProperty(id);
        Map<String, Object> values = ColumnValues.coerce("property", PROPERTY_COLUMNS, body, false);
        if (values.isEmpty()) {
            throw new InvalidRequestException("Nothing to change: provide at least one field");
        }
        validateProperty(values, id);
        updateColumns("property", PROPERTY_COLUMNS, values, id);
        return get(id, false, null);
    }

    @Override
    @Transactional
    public void deactivate(int id) {
        int updated = jdbc.sql("update property set active = false where id = :id").param("id", id).update();
        if (updated == 0) {
            throw propertyNotFound(id);
        }
    }

    // ------------------------------------------------------------------ room types

    @Override
    @Transactional
    public RoomTypeResponse addRoomType(int propertyId, Map<String, Object> body) {
        requireProperty(propertyId);
        Map<String, Object> values = ColumnValues.coerce("room type", ROOM_TYPE_COLUMNS, body, true);
        validateRoomType(values, propertyId, null);

        List<String> columns = new ArrayList<>(List.of("property_id"));
        List<String> placeholders = new ArrayList<>(List.of(":propertyId"));
        Map<String, Object> params = new HashMap<>(Map.of("propertyId", propertyId));
        values.forEach((json, value) -> {
            columns.add(column(ROOM_TYPE_COLUMNS, json).db());
            placeholders.add(":" + json);
            params.put(json, value);
        });
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("insert into room_type (" + String.join(", ", columns) + ") values ("
                + String.join(", ", placeholders) + ")").params(params).update(keyHolder, "id");
        return roomType(keyHolder.getKey().intValue());
    }

    @Override
    @Transactional
    public RoomTypeResponse updateRoomType(int roomTypeId, Map<String, Object> body) {
        int propertyId = roomTypeOwner(roomTypeId);
        Map<String, Object> values = ColumnValues.coerce("room type", ROOM_TYPE_COLUMNS, body, false);
        if (values.isEmpty()) {
            throw new InvalidRequestException("Nothing to change: provide at least one field");
        }
        validateRoomType(values, propertyId, roomTypeId);
        updateColumns("room_type", ROOM_TYPE_COLUMNS, values, roomTypeId);
        return roomType(roomTypeId);
    }

    @Override
    @Transactional
    public void deleteRoomType(int roomTypeId) {
        roomTypeOwner(roomTypeId);
        try {
            jdbc.sql("delete from room_type where id = :id").param("id", roomTypeId).update();
        } catch (DataIntegrityViolationException ex) {
            if (SqlErrors.FOREIGN_KEY_VIOLATION.equals(SqlErrors.sqlState(ex))) {
                throw new ConflictException("IN_USE",
                        "This room type still has rates; remove or end those rates first");
            }
            throw ex;
        }
    }

    // ------------------------------------------------------------------ replace-the-set collections

    @Override
    @Transactional
    public List<ChildRuleResponse> replaceChildRules(int propertyId, List<ChildRuleRequest> rules) {
        requireProperty(propertyId);
        requireList(rules);
        Map<String, String> errors = new LinkedHashMap<>();

        for (int i = 0; i < rules.size(); i++) {
            ChildRuleRequest rule = rules.get(i);
            String at = "[" + i + "].";
            if (rule == null) {
                errors.put("[" + i + "]", "must not be empty");
                continue;
            }
            checkAge(rule.minAge(), at + "minAge", errors);
            checkAge(rule.maxAge(), at + "maxAge", errors);
            if (rule.minAge() != null && rule.maxAge() != null && rule.maxAge() < rule.minAge()
                    && !errors.containsKey(at + "maxAge")) {
                errors.put(at + "maxAge", "must not be less than minAge");
            }
            if (rule.requiresBed() == null) {
                errors.put(at + "requiresBed", "is required");
            }
            if (rule.cost() == null) {
                errors.put(at + "cost", "is required");
            } else {
                checkMoney(rule.cost(), at + "cost", errors);
            }
        }
        if (errors.isEmpty()) {
            // Age bands must not overlap, or a child's age would match two rules.
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < rules.size(); i++) {
                order.add(i);
            }
            order.sort(Comparator.comparingInt((Integer i) -> rules.get(i).minAge()));
            for (int k = 1; k < order.size(); k++) {
                int previous = order.get(k - 1);
                int current = order.get(k);
                if (rules.get(current).minAge() <= rules.get(previous).maxAge()) {
                    errors.put("[" + current + "].minAge", "age range overlaps the rule at [" + previous + "]");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }

        jdbc.sql("delete from property_child_rule where property_id = :id").param("id", propertyId).update();
        for (ChildRuleRequest rule : rules) {
            jdbc.sql("""
                            insert into property_child_rule (property_id, min_age, max_age, requires_bed, cost)
                            values (:id, :minAge, :maxAge, :requiresBed, :cost)""")
                    .param("id", propertyId).param("minAge", rule.minAge()).param("maxAge", rule.maxAge())
                    .param("requiresBed", rule.requiresBed()).param("cost", rule.cost().setScale(2)).update();
        }
        return childRules(propertyId);
    }

    @Override
    @Transactional
    public List<PropertyInclusionResponse> replaceInclusions(int propertyId, List<PropertyInclusionRequest> items) {
        requireProperty(propertyId);
        requireList(items);
        Map<String, String> errors = new LinkedHashMap<>();
        Set<Integer> seen = new HashSet<>();
        List<Integer> ids = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            PropertyInclusionRequest item = items.get(i);
            String at = "[" + i + "].";
            if (item == null || item.inclusionId() == null) {
                errors.put(at + "inclusionId", "is required");
                continue;
            }
            if (!seen.add(item.inclusionId())) {
                errors.put(at + "inclusionId", "is listed more than once");
            }
            ids.add(item.inclusionId());
            if (item.cost() != null) {
                checkMoney(item.cost(), at + "cost", errors);
            }
        }
        if (errors.isEmpty()) {
            Set<Integer> existing = existingIds("hotel_inclusion", ids);
            for (int i = 0; i < items.size(); i++) {
                if (!existing.contains(items.get(i).inclusionId())) {
                    errors.put("[" + i + "].inclusionId", "is not a known hotel inclusion");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }

        jdbc.sql("delete from property_inclusion where property_id = :id").param("id", propertyId).update();
        for (PropertyInclusionRequest item : items) {
            jdbc.sql("insert into property_inclusion (property_id, inclusion_id, cost) values (:id, :inclusionId, :cost)")
                    .param("id", propertyId).param("inclusionId", item.inclusionId())
                    .param("cost", item.cost() == null ? null : item.cost().setScale(2)).update();
        }
        return inclusions(propertyId);
    }

    @Override
    @Transactional
    public List<DietarySupportResponse> replaceDietary(int propertyId, List<DietarySupportRequest> items) {
        requireProperty(propertyId);
        requireList(items);
        Map<String, String> errors = new LinkedHashMap<>();
        Set<Integer> seen = new HashSet<>();
        List<Integer> ids = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            DietarySupportRequest item = items.get(i);
            String at = "[" + i + "].";
            if (item == null || item.dietaryCategoryId() == null) {
                errors.put(at + "dietaryCategoryId", "is required");
                continue;
            }
            if (!seen.add(item.dietaryCategoryId())) {
                errors.put(at + "dietaryCategoryId", "is listed more than once");
            }
            ids.add(item.dietaryCategoryId());
            if (item.supported() == null) {
                errors.put(at + "supported", "is required");
            }
        }
        if (errors.isEmpty()) {
            Set<Integer> existing = existingIds("dietary_category", ids);
            for (int i = 0; i < items.size(); i++) {
                if (!existing.contains(items.get(i).dietaryCategoryId())) {
                    errors.put("[" + i + "].dietaryCategoryId", "is not a known dietary category");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }

        jdbc.sql("delete from property_dietary_capability where property_id = :id").param("id", propertyId).update();
        for (DietarySupportRequest item : items) {
            jdbc.sql("""
                            insert into property_dietary_capability (property_id, dietary_category_id, supported)
                            values (:id, :categoryId, :supported)""")
                    .param("id", propertyId).param("categoryId", item.dietaryCategoryId())
                    .param("supported", item.supported()).update();
        }
        return dietary(propertyId);
    }

    @Override
    @Transactional
    public BabyCotResponse setBabyCot(int propertyId, BabyCotRequest request) {
        requireProperty(propertyId);
        Map<String, String> errors = new LinkedHashMap<>();
        if (request == null || request.available() == null) {
            errors.put("available", "is required");
        }
        if (request != null && request.cost() != null) {
            checkMoney(request.cost(), "cost", errors);
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }
        // A cot that is not offered has no price.
        BigDecimal cost = request.available() && request.cost() != null ? request.cost().setScale(2) : null;

        int updated = jdbc.sql("update baby_cot_option set available = :available, cost = :cost where property_id = :id")
                .param("available", request.available()).param("cost", cost).param("id", propertyId).update();
        if (updated == 0) {
            jdbc.sql("insert into baby_cot_option (property_id, available, cost) values (:id, :available, :cost)")
                    .param("id", propertyId).param("available", request.available()).param("cost", cost).update();
        }
        return new BabyCotResponse(request.available(), cost);
    }

    // ------------------------------------------------------------------ validation

    private void validateProperty(Map<String, Object> values, Integer selfId) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (values.containsKey("active") && values.get("active") == null) {
            errors.put("active", "must be true or false");
        }
        if (values.get("starRating") instanceof Integer stars && (stars < 1 || stars > 5)) {
            errors.put("starRating", "must be between 1 and 5");
        }
        if (values.get("email") instanceof String email && !EMAIL.matcher(email).matches()) {
            errors.put("email", "is not a valid email address");
        }
        if (values.get("destinationId") instanceof Integer destinationId
                && existingIds("destination", List.of(destinationId)).isEmpty()) {
            errors.put("destinationId", "is not a known destination");
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }

        if (values.get("code") instanceof String code) {
            Long clash = selfId == null
                    ? jdbc.sql("select count(*) from property where code = :code").param("code", code)
                            .query(Long.class).single()
                    : jdbc.sql("select count(*) from property where code = :code and id <> :id").param("code", code)
                            .param("id", selfId).query(Long.class).single();
            if (clash > 0) {
                throw new ConflictException("DUPLICATE", "Property code '" + code + "' is already used by another hotel");
            }
        }
    }

    private void validateRoomType(Map<String, Object> values, int propertyId, Integer selfId) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (values.containsKey("extraBedAllowed") && values.get("extraBedAllowed") == null) {
            errors.put("extraBedAllowed", "must be true or false");
        }
        if (values.get("baseCapacity") instanceof Integer capacity && (capacity < 1 || capacity > 30)) {
            errors.put("baseCapacity", "must be between 1 and 30");
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }
        if (values.get("name") instanceof String name) {
            Long clash = jdbc.sql("""
                            select count(*) from room_type
                             where property_id = :propertyId and lower(name) = :name and id <> :self""")
                    .param("propertyId", propertyId).param("name", name.toLowerCase())
                    .param("self", selfId == null ? -1 : selfId).query(Long.class).single();
            if (clash > 0) {
                throw new ConflictException("DUPLICATE", "This hotel already has a room type named '" + name + "'");
            }
        }
    }

    private void checkAge(Integer age, String field, Map<String, String> errors) {
        if (age == null) {
            errors.put(field, "is required");
        } else if (age < 0 || age > 17) {
            errors.put(field, "must be between 0 and 17");
        }
    }

    private void checkMoney(BigDecimal value, String field, Map<String, String> errors) {
        if (value.signum() < 0) {
            errors.put(field, "must not be negative");
        } else if (value.stripTrailingZeros().scale() > 2) {
            errors.put(field, "must have at most 2 decimal places");
        } else if (value.compareTo(MAX_MONEY) > 0) {
            errors.put(field, "must not exceed " + MAX_MONEY.toPlainString());
        }
    }

    private void requireList(List<?> list) {
        if (list == null) {
            throw new InvalidRequestException("The request body must be a list (use [] to clear it)");
        }
    }

    // ------------------------------------------------------------------ helpers

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
            parts.add("p.name asc");
        }
        parts.add("p.id asc");
        return String.join(", ", parts);
    }

    private void updateColumns(String table, List<Column> columns, Map<String, Object> values, int id) {
        List<String> assignments = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            assignments.add(column(columns, json).db() + " = :" + json);
            params.put(json, value);
        });
        params.put("rowId", id);
        jdbc.sql("update " + table + " set " + String.join(", ", assignments) + " where id = :rowId")
                .params(params).update();
    }

    private Column column(List<Column> columns, String json) {
        return columns.stream().filter(c -> c.json().equals(json)).findFirst().orElseThrow();
    }

    private Set<Integer> existingIds(String table, Collection<Integer> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("select id from " + table + " where id in (:ids)").param("ids", ids)
                .query(Integer.class).list());
    }

    private void requireProperty(int id) {
        Long found = jdbc.sql("select count(*) from property where id = :id").param("id", id)
                .query(Long.class).single();
        if (found == 0) {
            throw propertyNotFound(id);
        }
    }

    private NotFoundException propertyNotFound(int id) {
        return new NotFoundException("No property found with id '" + id + "'");
    }

    private int roomTypeOwner(int roomTypeId) {
        return jdbc.sql("select property_id from room_type where id = :id").param("id", roomTypeId)
                .query(Integer.class).optional()
                .orElseThrow(() -> new NotFoundException("No room type found with id '" + roomTypeId + "'"));
    }

    private RoomTypeResponse roomType(int id) {
        return jdbc.sql("select id, name, base_capacity, extra_bed_allowed from room_type where id = :id")
                .param("id", id)
                .query((rs, n) -> new RoomTypeResponse(rs.getInt("id"), rs.getString("name"),
                        rs.getObject("base_capacity", Integer.class), rs.getBoolean("extra_bed_allowed")))
                .single();
    }

    private List<ChildRuleResponse> childRules(int propertyId) {
        return jdbc.sql("""
                        select id, min_age, max_age, requires_bed, cost from property_child_rule
                         where property_id = :id order by min_age, id""").param("id", propertyId)
                .query((rs, n) -> new ChildRuleResponse(rs.getInt("id"), rs.getInt("min_age"), rs.getInt("max_age"),
                        rs.getBoolean("requires_bed"), rs.getBigDecimal("cost"))).list();
    }

    private List<PropertyInclusionResponse> inclusions(int propertyId) {
        return jdbc.sql("""
                        select pi.inclusion_id, hi.name, pi.cost
                          from property_inclusion pi join hotel_inclusion hi on hi.id = pi.inclusion_id
                         where pi.property_id = :id order by hi.name""").param("id", propertyId)
                .query((rs, n) -> new PropertyInclusionResponse(
                        new RefResponse(rs.getInt("inclusion_id"), rs.getString("name")), rs.getBigDecimal("cost")))
                .list();
    }

    private List<DietarySupportResponse> dietary(int propertyId) {
        return jdbc.sql("""
                        select pd.dietary_category_id, dc.name, pd.supported
                          from property_dietary_capability pd join dietary_category dc on dc.id = pd.dietary_category_id
                         where pd.property_id = :id order by dc.id""").param("id", propertyId)
                .query((rs, n) -> new DietarySupportResponse(
                        new RefResponse(rs.getInt("dietary_category_id"), rs.getString("name")),
                        rs.getBoolean("supported"))).list();
    }
}
