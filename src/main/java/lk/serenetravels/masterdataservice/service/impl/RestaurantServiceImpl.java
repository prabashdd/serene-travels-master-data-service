package lk.serenetravels.masterdataservice.service.impl;

import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.dto.RestaurantResponse;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.RestaurantService;
import lk.serenetravels.masterdataservice.util.Coerce;
import lk.serenetravels.masterdataservice.util.ColumnValues;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Column;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Type;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RestaurantServiceImpl implements RestaurantService {

    private static final int MAX_PAGE_SIZE = 200;

    // Week order, Monday first, as people read a week. The stored number follows the database's own
    // documented convention for restaurant_closed_day.day_of_week: 0 = Sunday .. 6 = Saturday.
    private static final Map<String, Integer> DAY_NUMBERS = new LinkedHashMap<>();

    static {
        DAY_NUMBERS.put("MONDAY", 1);
        DAY_NUMBERS.put("TUESDAY", 2);
        DAY_NUMBERS.put("WEDNESDAY", 3);
        DAY_NUMBERS.put("THURSDAY", 4);
        DAY_NUMBERS.put("FRIDAY", 5);
        DAY_NUMBERS.put("SATURDAY", 6);
        DAY_NUMBERS.put("SUNDAY", 0);
    }

    private static final List<Column> COLUMNS = List.of(
            new Column("name", "name", Type.TEXT, true),
            new Column("destinationId", "destination_id", Type.INT, true));

    private static final Map<String, String> SORTS = Map.of("name", "r.name", "destination", "d.name", "id", "r.id");

    private static final String SELECT = """
            select r.id, r.name, r.destination_id, d.name as destination_name
              from restaurant r join destination d on d.id = r.destination_id""";

    private final JdbcClient jdbc;

    public RestaurantServiceImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public PageResponse<RestaurantResponse> list(String q, Integer destinationId, Integer dietaryCategoryId, String closedOn,
                                                 String openOn, List<String> sort, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        Map<String, Object> params = new HashMap<>();
        List<String> where = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where.add("lower(r.name) like :q escape '\\'");
            params.put("q", "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (destinationId != null) {
            where.add("r.destination_id = :destinationId");
            params.put("destinationId", destinationId);
        }
        if (dietaryCategoryId != null) {
            where.add("exists (select 1 from restaurant_dietary_capability c where c.restaurant_id = r.id "
                    + "and c.dietary_category_id = :dietaryCategoryId)");
            params.put("dietaryCategoryId", dietaryCategoryId);
        }
        if (closedOn != null) {
            where.add("exists (select 1 from restaurant_closed_day x where x.restaurant_id = r.id and x.day_of_week = :closedOn)");
            params.put("closedOn", dayNumber(closedOn, "closedOn"));
        }
        if (openOn != null) {
            where.add("not exists (select 1 from restaurant_closed_day y where y.restaurant_id = r.id and y.day_of_week = :openOn)");
            params.put("openOn", dayNumber(openOn, "openOn"));
        }
        String whereSql = where.isEmpty() ? "" : " where " + String.join(" and ", where);

        Long total = jdbc.sql("select count(*) from restaurant r" + whereSql).params(params).query(Long.class).single();

        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<RestaurantResponse> rows = jdbc.sql(SELECT + whereSql + " order by " + orderBy(sort) + " limit :limit offset :offset")
                .params(params).query((rs, n) -> map(rs)).list();
        return PageResponse.of(withDetails(rows), total, page, size);
    }

    @Override
    public RestaurantResponse get(int id) {
        RestaurantResponse row = jdbc.sql(SELECT + " where r.id = :id").param("id", id).query((rs, n) -> map(rs))
                .optional().orElseThrow(() -> notFound(id));
        return withDetails(List.of(row)).get(0);
    }

    // ------------------------------------------------------------------ writes

    @Override
    @Transactional
    public RestaurantResponse create(Map<String, Object> body) {
        Map<String, Object> fields = new LinkedHashMap<>(body);
        Object rawDays = fields.remove("closedDays");
        Object rawDietary = fields.remove("dietaryCategoryIds");

        Map<String, Object> values = ColumnValues.coerce("restaurant", COLUMNS, fields, true);
        Map<String, String> errors = new LinkedHashMap<>();
        List<Integer> days = rawDays == null ? List.of() : tryParse(() -> parseDays(rawDays, "closedDays"), errors);
        List<Integer> dietary = rawDietary == null ? List.of() : tryParse(() -> parseIds(rawDietary, "dietaryCategoryIds"), errors);
        validate(values, null, errors);
        checkDietaryExists(dietary, "dietaryCategoryIds", errors);
        throwIfAny(errors);
        checkNameFree(values, null);

        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("insert into restaurant (name, destination_id) values (:name, :destinationId)")
                .params(values).update(keyHolder, "id");
        int id = keyHolder.getKey().intValue();
        insertDays(id, days);
        insertDietary(id, dietary);
        return get(id);
    }

    @Override
    @Transactional
    public RestaurantResponse update(int id, Map<String, Object> body) {
        RestaurantResponse existing = get(id);
        Map<String, Object> values = ColumnValues.coerce("restaurant", COLUMNS, body, false);
        if (values.isEmpty()) {
            throw new InvalidRequestException("Nothing to change: provide at least one field");
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("name", existing.name());
        merged.put("destinationId", existing.destination().id());
        merged.putAll(values);

        Map<String, String> errors = new LinkedHashMap<>();
        validate(merged, id, errors);
        throwIfAny(errors);
        checkNameFree(merged, id);

        List<String> assignments = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            assignments.add(column(json).db() + " = :" + json);
            params.put(json, value);
        });
        params.put("rowId", id);
        jdbc.sql("update restaurant set " + String.join(", ", assignments) + " where id = :rowId").params(params).update();
        return get(id);
    }

    @Override
    @Transactional
    public void delete(int id) {
        get(id);
        jdbc.sql("delete from restaurant_closed_day where restaurant_id = :id").param("id", id).update();
        jdbc.sql("delete from restaurant_dietary_capability where restaurant_id = :id").param("id", id).update();
        jdbc.sql("delete from restaurant where id = :id").param("id", id).update();
    }

    @Override
    @Transactional
    public List<String> replaceClosedDays(int restaurantId, List<String> days) {
        get(restaurantId);
        if (days == null) {
            throw new InvalidRequestException("The request body must be a list of day names (use [] to clear it)");
        }
        List<Integer> numbers = parseDays(days, "days");
        jdbc.sql("delete from restaurant_closed_day where restaurant_id = :id").param("id", restaurantId).update();
        insertDays(restaurantId, numbers);
        return dayNames(numbers);
    }

    @Override
    @Transactional
    public List<RefResponse> replaceDietary(int restaurantId, List<Integer> dietaryCategoryIds) {
        get(restaurantId);
        if (dietaryCategoryIds == null) {
            throw new InvalidRequestException("The request body must be a list of dietary category ids (use [] to clear it)");
        }
        List<Integer> ids = parseIds(dietaryCategoryIds, "dietaryCategoryIds");
        Map<String, String> errors = new LinkedHashMap<>();
        checkDietaryExists(ids, "dietaryCategoryIds", errors);
        throwIfAny(errors);

        jdbc.sql("delete from restaurant_dietary_capability where restaurant_id = :id").param("id", restaurantId).update();
        insertDietary(restaurantId, ids);
        return dietary(List.of(restaurantId)).getOrDefault(restaurantId, List.of());
    }

    // ------------------------------------------------------------------ validation

    private void validate(Map<String, Object> values, Integer selfId, Map<String, String> errors) {
        if (values.get("destinationId") instanceof Integer destinationId) {
            Long found = jdbc.sql("select count(*) from destination where id = :id").param("id", destinationId)
                    .query(Long.class).single();
            if (found == 0) {
                errors.put("destinationId", "is not a known destination");
            }
        }
    }

    // A restaurant name is unique within its destination (the same name may exist elsewhere).
    private void checkNameFree(Map<String, Object> values, Integer selfId) {
        Long clash = jdbc.sql("""
                        select count(*) from restaurant
                         where destination_id = :destinationId and lower(name) = :name and id <> :self""")
                .param("destinationId", values.get("destinationId"))
                .param("name", ((String) values.get("name")).toLowerCase())
                .param("self", selfId == null ? -1 : selfId).query(Long.class).single();
        if (clash > 0) {
            throw new ConflictException("DUPLICATE", "This destination already has a restaurant named '" + values.get("name") + "'");
        }
    }

    private void checkDietaryExists(List<Integer> ids, String field, Map<String, String> errors) {
        if (ids.isEmpty()) {
            return;
        }
        Set<Integer> existing = new HashSet<>(jdbc.sql("select id from dietary_category where id in (:ids)")
                .param("ids", ids).query(Integer.class).list());
        for (int i = 0; i < ids.size(); i++) {
            if (!existing.contains(ids.get(i))) {
                errors.put(field + "[" + i + "]", "is not a known dietary category");
            }
        }
    }

    // Day names in any case ("monday", "Monday") -> the stored number. Unknown or repeated days are reported per item.
    private List<Integer> parseDays(Object raw, String field) {
        if (!(raw instanceof List<?> list)) {
            throw InvalidRequestException.field(field, "must be a list of day names, e.g. [\"MONDAY\", \"TUESDAY\"]");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        Set<Integer> seen = new HashSet<>();
        List<Integer> numbers = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            Integer number = item instanceof String text ? DAY_NUMBERS.get(text.trim().toUpperCase()) : null;
            if (number == null) {
                errors.put(field + "[" + i + "]", "must be one of " + DAY_NUMBERS.keySet());
            } else if (!seen.add(number)) {
                errors.put(field + "[" + i + "]", "is listed more than once");
            } else {
                numbers.add(number);
            }
        }
        throwIfAny(errors);
        return numbers;
    }

    private List<Integer> parseIds(Object raw, String field) {
        if (!(raw instanceof List<?> list)) {
            throw InvalidRequestException.field(field, "must be a list of ids");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        Set<Integer> seen = new HashSet<>();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            try {
                Integer id = Coerce.toInt(list.get(i), field + "[" + i + "]");
                if (!seen.add(id)) {
                    errors.put(field + "[" + i + "]", "is listed more than once");
                }
                ids.add(id);
            } catch (InvalidRequestException ex) {
                errors.putAll(ex.getFieldErrors());
            }
        }
        throwIfAny(errors);
        return ids;
    }

    // Runs a parser and, if it rejects the input, records its field errors instead of throwing - so one
    // request can report problems in several fields at once.
    private <T> List<T> tryParse(java.util.function.Supplier<List<T>> parser, Map<String, String> errors) {
        try {
            return parser.get();
        } catch (InvalidRequestException ex) {
            errors.putAll(ex.getFieldErrors());
            return List.of();
        }
    }

    private void throwIfAny(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }
    }

    private int dayNumber(String name, String field) {
        Integer number = DAY_NUMBERS.get(name.trim().toUpperCase());
        if (number == null) {
            throw InvalidRequestException.field(field, "must be one of " + DAY_NUMBERS.keySet());
        }
        return number;
    }

    // ------------------------------------------------------------------ helpers

    private void insertDays(int restaurantId, List<Integer> numbers) {
        for (Integer number : numbers) {
            jdbc.sql("insert into restaurant_closed_day (restaurant_id, day_of_week) values (:id, :day)")
                    .param("id", restaurantId).param("day", number).update();
        }
    }

    private void insertDietary(int restaurantId, List<Integer> categoryIds) {
        for (Integer categoryId : categoryIds) {
            jdbc.sql("insert into restaurant_dietary_capability (restaurant_id, dietary_category_id) values (:id, :category)")
                    .param("id", restaurantId).param("category", categoryId).update();
        }
    }

    // Stored numbers -> day names, in week order (Monday first).
    private List<String> dayNames(Iterable<Integer> numbers) {
        Set<Integer> wanted = new HashSet<>();
        numbers.forEach(wanted::add);
        return DAY_NUMBERS.entrySet().stream().filter(e -> wanted.contains(e.getValue())).map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private Map<Integer, List<RefResponse>> dietary(List<Integer> restaurantIds) {
        Map<Integer, List<RefResponse>> byRestaurant = new HashMap<>();
        jdbc.sql("""
                        select c.restaurant_id, d.id, d.name from restaurant_dietary_capability c
                          join dietary_category d on d.id = c.dietary_category_id
                         where c.restaurant_id in (:ids) order by c.restaurant_id, d.id""")
                .param("ids", restaurantIds)
                .query((rs, n) -> {
                    byRestaurant.computeIfAbsent(rs.getInt("restaurant_id"), k -> new ArrayList<>())
                            .add(new RefResponse(rs.getInt("id"), rs.getString("name")));
                    return null;
                }).list();
        return byRestaurant;
    }

    // One query each for the whole page: closed days and dietary support per restaurant.
    private List<RestaurantResponse> withDetails(List<RestaurantResponse> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        List<Integer> ids = rows.stream().map(RestaurantResponse::id).toList();
        Map<Integer, List<Integer>> daysById = new HashMap<>();
        jdbc.sql("select restaurant_id, day_of_week from restaurant_closed_day where restaurant_id in (:ids)")
                .param("ids", ids)
                .query((rs, n) -> {
                    daysById.computeIfAbsent(rs.getInt("restaurant_id"), k -> new ArrayList<>()).add(rs.getInt("day_of_week"));
                    return null;
                }).list();
        Map<Integer, List<RefResponse>> dietaryById = dietary(ids);
        return rows.stream().map(r -> new RestaurantResponse(r.id(), r.name(), r.destination(),
                dayNames(daysById.getOrDefault(r.id(), List.of())), dietaryById.getOrDefault(r.id(), List.of()))).toList();
    }

    private RestaurantResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RestaurantResponse(rs.getInt("id"), rs.getString("name"),
                new RefResponse(rs.getInt("destination_id"), rs.getString("destination_name")), List.of(), List.of());
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
            parts.add("r.name asc");
        }
        parts.add("r.id asc");
        return String.join(", ", parts);
    }

    private Column column(String json) {
        return COLUMNS.stream().filter(c -> c.json().equals(json)).findFirst().orElseThrow();
    }

    private NotFoundException notFound(int id) {
        return new NotFoundException("No restaurant found with id '" + id + "'");
    }
}
