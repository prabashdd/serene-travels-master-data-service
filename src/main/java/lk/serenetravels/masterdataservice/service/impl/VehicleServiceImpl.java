package lk.serenetravels.masterdataservice.service.impl;

import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.dto.VehicleResponse;
import lk.serenetravels.masterdataservice.dto.VehicleWarning;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.VehicleService;
import lk.serenetravels.masterdataservice.util.ColumnValues;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Column;
import lk.serenetravels.masterdataservice.util.LookupDefinitions.Type;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VehicleServiceImpl implements VehicleService {

    private static final int MAX_PAGE_SIZE = 200;
    // A document that expires within this many days is flagged as "expiring".
    private static final int EXPIRY_WARNING_DAYS = 30;
    public static final String INTERNAL = "INTERNAL";
    public static final String CONTRACTED = "CONTRACTED";
    private static final List<String> METHODS = List.of(INTERNAL, CONTRACTED);

    private static final List<Column> COLUMNS = List.of(
            new Column("registrationNo", "registration_no", Type.TEXT, true),
            new Column("vehicleTypeId", "vehicle_type_id", Type.INT, true),
            new Column("seatingCapacity", "seating_capacity", Type.INT, true),
            new Column("method", "method", Type.TEXT, true),
            new Column("ownerName", "owner_name", Type.TEXT, false),
            new Column("insuranceCompany", "insurance_company", Type.TEXT, false),
            new Column("policyNumber", "policy_number", Type.TEXT, false),
            new Column("insuranceExpiryDate", "insurance_expiry_date", Type.DATE, false),
            new Column("revenueLicenseExpiryDate", "revenue_license_expiry_date", Type.DATE, false),
            new Column("driverId", "driver_id", Type.INT, false),
            new Column("assistantId", "assistant_id", Type.INT, false),
            new Column("active", "active", Type.BOOL, false));

    private static final Map<String, String> SORTS = Map.of(
            "registrationNo", "v.registration_no", "type", "t.name", "seatingCapacity", "v.seating_capacity",
            "insuranceExpiry", "v.insurance_expiry_date", "licenseExpiry", "v.revenue_license_expiry_date", "id", "v.id");

    private static final String SELECT = """
            select v.id, v.registration_no, v.vehicle_type_id, t.name as type_name, v.seating_capacity, v.method,
                   v.owner_name, v.insurance_company, v.policy_number, v.insurance_expiry_date,
                   v.revenue_license_expiry_date, v.driver_id, d.name as driver_name, d.active as driver_active,
                   v.assistant_id, a.name as assistant_name, a.active as assistant_active, v.active
              from vehicle v
              join vehicle_type t on t.id = v.vehicle_type_id
              left join driver d on d.id = v.driver_id
              left join driver a on a.id = v.assistant_id""";

    private final JdbcClient jdbc;
    private final Clock clock;

    public VehicleServiceImpl(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public PageResponse<VehicleResponse> list(String q, Integer vehicleTypeId, String method, Boolean active,
                                              Integer expiringWithinDays, List<String> sort, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        if (method != null && !METHODS.contains(method)) {
            throw InvalidRequestException.field("method", "must be one of " + METHODS);
        }
        if (expiringWithinDays != null && (expiringWithinDays < 0 || expiringWithinDays > 3650)) {
            throw InvalidRequestException.field("expiringWithinDays", "must be between 0 and 3650");
        }
        Map<String, Object> params = new HashMap<>();
        List<String> where = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where.add("(lower(v.registration_no) like :q escape '\\' or lower(v.owner_name) like :q escape '\\')");
            params.put("q", "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (vehicleTypeId != null) {
            where.add("v.vehicle_type_id = :vehicleTypeId");
            params.put("vehicleTypeId", vehicleTypeId);
        }
        if (method != null) {
            where.add("v.method = :method");
            params.put("method", method);
        }
        if (active != null) {
            where.add("v.active = :active");
            params.put("active", active);
        }
        if (expiringWithinDays != null) {
            where.add("(v.insurance_expiry_date <= :limit or v.revenue_license_expiry_date <= :limit)");
            params.put("limit", LocalDate.now(clock).plusDays(expiringWithinDays));
        }
        String whereSql = where.isEmpty() ? "" : " where " + String.join(" and ", where);

        Long total = jdbc.sql("select count(*) from vehicle v" + whereSql).params(params).query(Long.class).single();

        params.put("limit2", size);
        params.put("offset", (long) page * size);
        List<VehicleResponse> rows = jdbc.sql(SELECT + whereSql + " order by " + orderBy(sort)
                        + " limit :limit2 offset :offset")
                .params(params).query((rs, n) -> map(rs)).list();
        return PageResponse.of(rows, total, page, size);
    }

    @Override
    public VehicleResponse get(int id) {
        return jdbc.sql(SELECT + " where v.id = :id").param("id", id).query((rs, n) -> map(rs))
                .optional().orElseThrow(() -> notFound(id));
    }

    // ------------------------------------------------------------------ writes

    @Override
    @Transactional
    public VehicleResponse create(Map<String, Object> body) {
        Map<String, Object> values = ColumnValues.coerce("vehicle", COLUMNS, body, true);
        normalise(values);
        values.putIfAbsent("active", Boolean.TRUE);
        validate(values, values, null);

        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            columns.add(column(json).db());
            placeholders.add(":" + json);
            params.put(json, value);
        });
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("insert into vehicle (" + String.join(", ", columns) + ") values (" + String.join(", ", placeholders) + ")")
                .params(params).update(keyHolder, "id");
        return get(keyHolder.getKey().intValue());
    }

    @Override
    @Transactional
    public VehicleResponse update(int id, Map<String, Object> body) {
        VehicleResponse existing = get(id);
        Map<String, Object> values = ColumnValues.coerce("vehicle", COLUMNS, body, false);
        if (values.isEmpty()) {
            throw new InvalidRequestException("Nothing to change: provide at least one field");
        }
        normalise(values);

        // Rules span several fields (seats vs type), so check the row as it WILL look; but only fields the
        // caller actually changes are held to the "assigned person must be active" rule.
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("registrationNo", existing.registrationNo());
        merged.put("vehicleTypeId", existing.vehicleType().id());
        merged.put("seatingCapacity", existing.seatingCapacity());
        merged.put("method", existing.method());
        merged.put("active", existing.active());
        merged.putAll(values);
        validate(values, merged, id);

        List<String> assignments = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        values.forEach((json, value) -> {
            assignments.add(column(json).db() + " = :" + json);
            params.put(json, value);
        });
        params.put("rowId", id);
        jdbc.sql("update vehicle set " + String.join(", ", assignments) + " where id = :rowId").params(params).update();
        return get(id);
    }

    @Override
    @Transactional
    public void deactivate(int id) {
        int updated = jdbc.sql("update vehicle set active = false where id = :id").param("id", id).update();
        if (updated == 0) {
            throw notFound(id);
        }
    }

    // ------------------------------------------------------------------ validation

    // Registration numbers and method codes are stored in the canonical upper case.
    private void normalise(Map<String, Object> values) {
        for (String field : List.of("registrationNo", "method")) {
            if (values.get(field) instanceof String text) {
                values.put(field, text.toUpperCase());
            }
        }
    }

    // `changed` = the fields being written; `merged` = the complete row as it will look (same map on create).
    private void validate(Map<String, Object> changed, Map<String, Object> merged, Integer selfId) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (merged.get("active") == null) {
            errors.put("active", "must be true or false");
        }
        if (merged.get("method") == null || !METHODS.contains(merged.get("method"))) {
            errors.put("method", "must be one of " + METHODS);
        }
        Integer seats = (Integer) merged.get("seatingCapacity");
        if (seats < 1 || seats > 100) {
            errors.put("seatingCapacity", "must be between 1 and 100");
        }

        Integer typeId = (Integer) merged.get("vehicleTypeId");
        Integer maxCapacity = jdbc.sql("select max_capacity from vehicle_type where id = :id").param("id", typeId)
                .query(Integer.class).optional().orElse(null);
        if (maxCapacity == null) {
            errors.put("vehicleTypeId", "is not a known vehicle type");
        } else if (!errors.containsKey("seatingCapacity")
                && (changed.containsKey("seatingCapacity") || changed.containsKey("vehicleTypeId"))
                && seats > maxCapacity) {
            errors.put("seatingCapacity", "is more than this vehicle type carries (" + maxCapacity + ")");
        }

        checkPerson(changed, "driverId", DriverServiceImpl.DRIVER, errors);
        checkPerson(changed, "assistantId", DriverServiceImpl.ASSISTANT, errors);

        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }

        Long clash = jdbc.sql("select count(*) from vehicle where upper(registration_no) = :reg and id <> :self")
                .param("reg", ((String) merged.get("registrationNo")).toUpperCase())
                .param("self", selfId == null ? -1 : selfId).query(Long.class).single();
        if (clash > 0) {
            throw new ConflictException("DUPLICATE", "Registration '" + merged.get("registrationNo") + "' is already in the fleet");
        }
    }

    // A vehicle's driver must be an active DRIVER and its assistant an active ASSISTANT.
    private void checkPerson(Map<String, Object> changed, String field, String requiredRole, Map<String, String> errors) {
        if (!(changed.get(field) instanceof Integer personId)) {
            return;
        }
        Map<String, Object> person = jdbc.sql("select role, active from driver where id = :id").param("id", personId)
                .query((rs, n) -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("role", rs.getString("role"));
                    map.put("active", rs.getBoolean("active"));
                    return map;
                }).optional().orElse(null);
        if (person == null) {
            errors.put(field, "is not a known " + requiredRole.toLowerCase());
        } else if (!requiredRole.equals(person.get("role"))) {
            errors.put(field, "must be someone whose role is " + requiredRole);
        } else if (!Boolean.TRUE.equals(person.get("active"))) {
            errors.put(field, "is inactive");
        }
    }

    // ------------------------------------------------------------------ helpers

    private VehicleResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        boolean active = rs.getBoolean("active");
        LocalDate insurance = rs.getObject("insurance_expiry_date", LocalDate.class);
        LocalDate license = rs.getObject("revenue_license_expiry_date", LocalDate.class);
        Integer driverId = rs.getObject("driver_id", Integer.class);
        Integer assistantId = rs.getObject("assistant_id", Integer.class);

        List<VehicleWarning> warnings = new ArrayList<>();
        if (active) {   // a vehicle that is out of service needs no attention
            LocalDate today = LocalDate.now(clock);
            addExpiryWarning(warnings, "INSURANCE", insurance, today);
            addExpiryWarning(warnings, "LICENSE", license, today);
            if (driverId == null) {
                warnings.add(new VehicleWarning("NO_DRIVER", null));
            } else if (!rs.getBoolean("driver_active")) {
                warnings.add(new VehicleWarning("DRIVER_INACTIVE", null));
            }
            if (assistantId != null && !rs.getBoolean("assistant_active")) {
                warnings.add(new VehicleWarning("ASSISTANT_INACTIVE", null));
            }
        }
        return new VehicleResponse(rs.getInt("id"), rs.getString("registration_no"),
                new RefResponse(rs.getInt("vehicle_type_id"), rs.getString("type_name")), rs.getInt("seating_capacity"),
                rs.getString("method"), rs.getString("owner_name"), rs.getString("insurance_company"),
                rs.getString("policy_number"), insurance, license,
                driverId == null ? null : new RefResponse(driverId, rs.getString("driver_name")),
                assistantId == null ? null : new RefResponse(assistantId, rs.getString("assistant_name")),
                active, warnings);
    }

    private void addExpiryWarning(List<VehicleWarning> warnings, String what, LocalDate expiry, LocalDate today) {
        if (expiry == null) {
            return;
        }
        int days = (int) ChronoUnit.DAYS.between(today, expiry);
        if (days < 0) {
            warnings.add(new VehicleWarning(what + "_EXPIRED", days));
        } else if (days <= EXPIRY_WARNING_DAYS) {
            warnings.add(new VehicleWarning(what + "_EXPIRING", days));
        }
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
            parts.add("v.registration_no asc");
        }
        parts.add("v.id asc");
        return String.join(", ", parts);
    }

    private Column column(String json) {
        return COLUMNS.stream().filter(c -> c.json().equals(json)).findFirst().orElseThrow();
    }

    private NotFoundException notFound(int id) {
        return new NotFoundException("No vehicle found with id '" + id + "'");
    }
}
