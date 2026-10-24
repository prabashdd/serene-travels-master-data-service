package lk.serenetravels.masterdataservice.service.impl;

import lk.serenetravels.masterdataservice.dto.DriverResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.DriverService;
import lk.serenetravels.masterdataservice.util.ColumnValues;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Column;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Type;
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
public class DriverServiceImpl implements DriverService {

    private static final int MAX_PAGE_SIZE = 200;
    public static final String DRIVER = "DRIVER";
    public static final String ASSISTANT = "ASSISTANT";
    private static final List<String> ROLES = List.of(DRIVER, ASSISTANT);
    private static final List<String> GENDERS = List.of("MALE", "FEMALE", "OTHER");

    private static final List<Column> COLUMNS = List.of(
            new Column("title", "title", Type.TEXT, false),
            new Column("name", "name", Type.TEXT, true),
            new Column("nameWithInitials", "name_with_initials", Type.TEXT, false),
            new Column("shortname", "shortname", Type.TEXT, false),
            new Column("gender", "gender", Type.TEXT, false),
            new Column("nic", "nic", Type.TEXT, true),
            new Column("dateOfBirth", "date_of_birth", Type.DATE, false),
            new Column("address", "address", Type.TEXT, false),
            new Column("contact", "contact", Type.TEXT, false),
            new Column("mobile2", "mobile_2", Type.TEXT, false),
            new Column("licenseNo", "license_no", Type.TEXT, false),
            new Column("role", "role", Type.TEXT, false),
            new Column("isChauffeur", "is_chauffeur", Type.BOOL, false),
            new Column("active", "active", Type.BOOL, false));

    private static final Map<String, String> SORTS = Map.of("name", "d.name", "role", "d.role", "nic", "d.nic", "id", "d.id");

    private static final String SELECT = """
            select d.id, d.title, d.name, d.name_with_initials, d.shortname, d.gender, d.nic, d.date_of_birth, d.address,
                   d.contact, d.mobile_2, d.license_no, d.role, d.is_chauffeur, d.active
              from driver d""";

    private final JdbcClient jdbc;
    private final Clock clock;

    public DriverServiceImpl(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public PageResponse<DriverResponse> list(String q, String role, Boolean active, Boolean chauffeur,
                                             List<String> sort, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        if (role != null && !ROLES.contains(role)) {
            throw InvalidRequestException.field("role", "must be one of " + ROLES);
        }
        Map<String, Object> params = new HashMap<>();
        List<String> where = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where.add("(lower(d.name) like :q escape '\\' or lower(d.nic) like :q escape '\\' "
                    + "or lower(d.contact) like :q escape '\\' or lower(d.license_no) like :q escape '\\')");
            params.put("q", "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (role != null) {
            where.add("d.role = :role");
            params.put("role", role);
        }
        if (active != null) {
            where.add("d.active = :active");
            params.put("active", active);
        }
        if (chauffeur != null) {
            where.add("d.is_chauffeur = :chauffeur");
            params.put("chauffeur", chauffeur);
        }
        String whereSql = where.isEmpty() ? "" : " where " + String.join(" and ", where);

        Long total = jdbc.sql("select count(*) from driver d" + whereSql).params(params).query(Long.class).single();

        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<DriverResponse> rows = jdbc.sql(SELECT + whereSql + " order by " + orderBy(sort) + " limit :limit offset :offset")
                .params(params).query((rs, n) -> map(rs)).list();
        return PageResponse.of(withVehicles(rows), total, page, size);
    }

    @Override
    public DriverResponse get(int id) {
        DriverResponse row = jdbc.sql(SELECT + " where d.id = :id").param("id", id).query((rs, n) -> map(rs))
                .optional().orElseThrow(() -> notFound(id));
        return withVehicles(List.of(row)).get(0);
    }

    // ------------------------------------------------------------------ writes

    @Override
    @Transactional
    public DriverResponse create(Map<String, Object> body) {
        Map<String, Object> values = ColumnValues.coerce("driver", COLUMNS, body, true);
        normalise(values);
        values.putIfAbsent("role", DRIVER);
        values.putIfAbsent("isChauffeur", Boolean.FALSE);
        values.putIfAbsent("active", Boolean.TRUE);
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
        jdbc.sql("insert into driver (" + String.join(", ", columns) + ") values (" + String.join(", ", placeholders) + ")")
                .params(params).update(keyHolder, "id");
        return get(keyHolder.getKey().intValue());
    }

    @Override
    @Transactional
    public DriverResponse update(int id, Map<String, Object> body) {
        DriverResponse existing = get(id);
        Map<String, Object> values = ColumnValues.coerce("driver", COLUMNS, body, false);
        if (values.isEmpty()) {
            throw new InvalidRequestException("Nothing to change: provide at least one field");
        }
        normalise(values);

        // Rules span several fields (role vs chauffeur), so check the row as it WILL look.
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("name", existing.name());
        merged.put("nic", existing.nic());
        merged.put("gender", existing.gender());
        merged.put("dateOfBirth", existing.dateOfBirth());
        merged.put("role", existing.role());
        merged.put("isChauffeur", existing.isChauffeur());
        merged.put("active", existing.active());
        merged.putAll(values);
        validate(merged, id);
        if (values.containsKey("role") && !existing.role().equals(values.get("role"))) {
            checkRoleChangeAllowed(id, (String) values.get("role"));
        }

        List<String> assignments = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            assignments.add(column(json).db() + " = :" + json);
            params.put(json, value);
        });
        params.put("rowId", id);
        jdbc.sql("update driver set " + String.join(", ", assignments) + " where id = :rowId").params(params).update();
        return get(id);
    }

    @Override
    @Transactional
    public void deactivate(int id) {
        int updated = jdbc.sql("update driver set active = false where id = :id").param("id", id).update();
        if (updated == 0) {
            throw notFound(id);
        }
    }

    // ------------------------------------------------------------------ validation

    // Codes people type in different cases are stored in the canonical upper case the database expects.
    private void normalise(Map<String, Object> values) {
        for (String field : List.of("gender", "role")) {
            if (values.get(field) instanceof String text) {
                values.put(field, text.toUpperCase());
            }
        }
    }

    private void validate(Map<String, Object> values, Integer selfId) {
        Map<String, String> errors = new LinkedHashMap<>();

        for (String flag : List.of("isChauffeur", "active")) {
            if (values.get(flag) == null) {
                errors.put(flag, "must be true or false");
            }
        }
        Object role = values.get("role");
        if (role == null || !ROLES.contains(role)) {
            errors.put("role", "must be one of " + ROLES);
        }
        if (values.get("gender") instanceof String gender && !GENDERS.contains(gender)) {
            errors.put("gender", "must be one of " + GENDERS);
        }
        if (values.get("dateOfBirth") instanceof LocalDate dob && dob.isAfter(LocalDate.now(clock))) {
            errors.put("dateOfBirth", "must not be in the future");
        }
        if (ASSISTANT.equals(role) && Boolean.TRUE.equals(values.get("isChauffeur"))) {
            errors.put("isChauffeur", "only applies to a DRIVER");
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }

        Long clash = jdbc.sql("select count(*) from driver where lower(trim(nic)) = :nic and id <> :self")
                .param("nic", ((String) values.get("nic")).trim().toLowerCase())
                .param("self", selfId == null ? -1 : selfId).query(Long.class).single();
        if (clash > 0) {
            throw new ConflictException("DUPLICATE", "NIC '" + values.get("nic") + "' already belongs to another person");
        }
    }

    // Vehicles hold a DRIVER in one slot and an ASSISTANT in the other; a person cannot change role under them.
    private void checkRoleChangeAllowed(int id, String newRole) {
        String slot = ASSISTANT.equals(newRole) ? "driver_id" : "assistant_id";
        List<String> blocking = jdbc.sql("select registration_no from vehicle where " + slot + " = :id order by registration_no")
                .param("id", id).query(String.class).list();
        if (!blocking.isEmpty()) {
            throw new ConflictException("IN_USE", "Cannot become " + newRole + " while assigned as "
                    + (ASSISTANT.equals(newRole) ? "driver" : "assistant") + " on " + String.join(", ", blocking)
                    + "; reassign those vehicles first");
        }
    }

    // ------------------------------------------------------------------ helpers

    // One query for the whole page: which vehicles each person is assigned to.
    private List<DriverResponse> withVehicles(List<DriverResponse> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        List<Integer> ids = rows.stream().map(DriverResponse::id).toList();
        Map<Integer, List<RefResponse>> byPerson = new HashMap<>();
        jdbc.sql("""
                        select id, registration_no, driver_id, assistant_id from vehicle
                         where driver_id in (:ids) or assistant_id in (:ids) order by registration_no""")
                .param("ids", ids)
                .query((rs, n) -> {
                    RefResponse vehicle = new RefResponse(rs.getInt("id"), rs.getString("registration_no"));
                    Integer driver = rs.getObject("driver_id", Integer.class);
                    Integer assistant = rs.getObject("assistant_id", Integer.class);
                    if (driver != null) {
                        byPerson.computeIfAbsent(driver, k -> new ArrayList<>()).add(vehicle);
                    }
                    if (assistant != null) {
                        byPerson.computeIfAbsent(assistant, k -> new ArrayList<>()).add(vehicle);
                    }
                    return vehicle;
                }).list();
        return rows.stream().map(r -> new DriverResponse(r.id(), r.title(), r.name(), r.nameWithInitials(), r.shortname(),
                r.gender(), r.nic(), r.dateOfBirth(), r.address(), r.contact(), r.mobile2(), r.licenseNo(), r.role(),
                r.isChauffeur(), r.active(), byPerson.getOrDefault(r.id(), List.of()))).collect(Collectors.toList());
    }

    private DriverResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DriverResponse(rs.getInt("id"), rs.getString("title"), rs.getString("name"),
                rs.getString("name_with_initials"), rs.getString("shortname"), rs.getString("gender"), rs.getString("nic"),
                rs.getObject("date_of_birth", LocalDate.class), rs.getString("address"), rs.getString("contact"),
                rs.getString("mobile_2"), rs.getString("license_no"), rs.getString("role"), rs.getBoolean("is_chauffeur"),
                rs.getBoolean("active"), List.of());
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
            parts.add("d.name asc");
        }
        parts.add("d.id asc");
        return String.join(", ", parts);
    }

    private Column column(String json) {
        return COLUMNS.stream().filter(c -> c.json().equals(json)).findFirst().orElseThrow();
    }

    private NotFoundException notFound(int id) {
        return new NotFoundException("No driver found with id '" + id + "'");
    }
}
