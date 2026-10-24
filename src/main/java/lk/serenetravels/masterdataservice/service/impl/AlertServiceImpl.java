package lk.serenetravels.masterdataservice.service.impl;

import lk.serenetravels.masterdataservice.dto.AlertResponse;
import lk.serenetravels.masterdataservice.dto.AlertSummaryResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RateFamilyResponse;
import lk.serenetravels.masterdataservice.dto.RateRowResponse;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.service.AlertService;
import lk.serenetravels.masterdataservice.service.RateService;
import lk.serenetravels.masterdataservice.util.RateFamilies;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AlertServiceImpl implements AlertService {

    private static final int DEFAULT_WITHIN_DAYS = 30;
    private static final int MAX_WITHIN_DAYS = 3650;
    private static final int MAX_PAGE_SIZE = 200;
    // A date within this many days is WARNING; further ahead (but inside the window) is INFO; already past is CRITICAL.
    private static final int WARNING_DAYS = 7;

    public static final String VEHICLE_INSURANCE_EXPIRING = "VEHICLE_INSURANCE_EXPIRING";
    public static final String VEHICLE_LICENSE_EXPIRING = "VEHICLE_LICENSE_EXPIRING";
    public static final String VEHICLE_NO_DRIVER = "VEHICLE_NO_DRIVER";
    public static final String VEHICLE_DRIVER_INACTIVE = "VEHICLE_DRIVER_INACTIVE";
    public static final String VEHICLE_ASSISTANT_INACTIVE = "VEHICLE_ASSISTANT_INACTIVE";
    public static final String RATE_ENDING_SOON = "RATE_ENDING_SOON";
    public static final String PROPERTY_NO_CURRENT_RATE = "PROPERTY_NO_CURRENT_RATE";
    public static final String ATTRACTION_NO_CURRENT_RATE = "ATTRACTION_NO_CURRENT_RATE";

    private static final List<String> CODES = List.of(VEHICLE_INSURANCE_EXPIRING, VEHICLE_LICENSE_EXPIRING,
            VEHICLE_NO_DRIVER, VEHICLE_DRIVER_INACTIVE, VEHICLE_ASSISTANT_INACTIVE, RATE_ENDING_SOON,
            PROPERTY_NO_CURRENT_RATE, ATTRACTION_NO_CURRENT_RATE);
    private static final List<String> SEVERITIES = List.of("CRITICAL", "WARNING", "INFO");

    private final JdbcClient jdbc;
    private final Clock clock;
    private final RateService rateService;

    public AlertServiceImpl(JdbcClient jdbc, Clock clock, RateService rateService) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.rateService = rateService;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public PageResponse<AlertResponse> list(Integer withinDays, String code, String severity, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        Map<String, String> errors = new LinkedHashMap<>();
        String codeFilter = code == null || code.isBlank() ? null : code.trim().toUpperCase();
        String severityFilter = severity == null || severity.isBlank() ? null : severity.trim().toUpperCase();
        if (codeFilter != null && !CODES.contains(codeFilter)) {
            errors.put("code", "must be one of " + CODES);
        }
        if (severityFilter != null && !SEVERITIES.contains(severityFilter)) {
            errors.put("severity", "must be one of " + SEVERITIES);
        }
        int days = days(withinDays, errors);
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }

        List<AlertResponse> matching = collect(days).stream()
                .filter(a -> codeFilter == null || a.code().equals(codeFilter))
                .filter(a -> severityFilter == null || a.severity().equals(severityFilter))
                .toList();
        int from = (int) Math.min((long) page * size, matching.size());
        int to = Math.min(from + size, matching.size());
        return PageResponse.of(matching.subList(from, to), matching.size(), page, size);
    }

    @Override
    public AlertSummaryResponse summary(Integer withinDays) {
        Map<String, String> errors = new LinkedHashMap<>();
        int days = days(withinDays, errors);
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Validation failed", errors);
        }
        List<AlertResponse> alerts = collect(days);
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        SEVERITIES.forEach(s -> bySeverity.put(s, 0));
        Map<String, Integer> byCode = new LinkedHashMap<>();
        CODES.forEach(c -> byCode.put(c, 0));
        for (AlertResponse alert : alerts) {
            bySeverity.merge(alert.severity(), 1, Integer::sum);
            byCode.merge(alert.code(), 1, Integer::sum);
        }
        return new AlertSummaryResponse(alerts.size(), bySeverity, byCode);
    }

    // ------------------------------------------------------------------ collecting

    private List<AlertResponse> collect(int withinDays) {
        LocalDate today = LocalDate.now(clock);
        List<AlertResponse> alerts = new ArrayList<>();
        vehicleAlerts(alerts, today, withinDays);
        rateEndingAlerts(alerts, today, withinDays);
        noCurrentRateAlerts(alerts, today, "property", "property_rate", "property_id", PROPERTY_NO_CURRENT_RATE, "room rate",
                "p.active");
        noCurrentRateAlerts(alerts, today, "attraction", "attraction_rate", "attraction_id", ATTRACTION_NO_CURRENT_RATE, "rate",
                "1 = 1");
        alerts.sort(Comparator
                .comparingInt((AlertResponse a) -> SEVERITIES.indexOf(a.severity()))
                .thenComparing(a -> a.daysLeft() == null ? Integer.MAX_VALUE : a.daysLeft())
                .thenComparing(a -> a.subject().name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(a -> CODES.indexOf(a.code())));
        return alerts;
    }

    // Active vehicles only: one that is out of service needs no attention.
    private void vehicleAlerts(List<AlertResponse> alerts, LocalDate today, int withinDays) {
        jdbc.sql("""
                        select v.id, v.registration_no, v.insurance_expiry_date, v.revenue_license_expiry_date,
                               v.driver_id, d.name as driver_name, d.active as driver_active,
                               v.assistant_id, a.name as assistant_name, a.active as assistant_active
                          from vehicle v
                          left join driver d on d.id = v.driver_id
                          left join driver a on a.id = v.assistant_id
                         where v.active
                         order by v.registration_no""")
                .query((rs, n) -> {
                    RefResponse vehicle = new RefResponse(rs.getInt("id"), rs.getString("registration_no"));
                    expiry(alerts, today, withinDays, VEHICLE_INSURANCE_EXPIRING, vehicle,
                            rs.getObject("insurance_expiry_date", LocalDate.class), "Insurance");
                    expiry(alerts, today, withinDays, VEHICLE_LICENSE_EXPIRING, vehicle,
                            rs.getObject("revenue_license_expiry_date", LocalDate.class), "Revenue licence");
                    if (rs.getObject("driver_id", Integer.class) == null) {
                        alerts.add(new AlertResponse(VEHICLE_NO_DRIVER, "WARNING", "vehicle", vehicle, null, null,
                                vehicle.name() + " has no driver assigned"));
                    } else if (!rs.getBoolean("driver_active")) {
                        alerts.add(new AlertResponse(VEHICLE_DRIVER_INACTIVE, "WARNING", "vehicle", vehicle, null, null,
                                "The driver of " + vehicle.name() + " (" + rs.getString("driver_name") + ") is inactive"));
                    }
                    if (rs.getObject("assistant_id", Integer.class) != null && !rs.getBoolean("assistant_active")) {
                        alerts.add(new AlertResponse(VEHICLE_ASSISTANT_INACTIVE, "INFO", "vehicle", vehicle, null, null,
                                "The assistant of " + vehicle.name() + " (" + rs.getString("assistant_name") + ") is inactive"));
                    }
                    return null;
                }).list();
    }

    private void expiry(List<AlertResponse> alerts, LocalDate today, int withinDays, String code, RefResponse vehicle,
                        LocalDate date, String what) {
        if (date == null) {
            return;
        }
        int days = (int) ChronoUnit.DAYS.between(today, date);
        if (days > withinDays) {
            return;
        }
        alerts.add(new AlertResponse(code, severityFor(days), "vehicle", vehicle, date, days,
                what + " of " + vehicle.name() + " " + when(days, date)));
    }

    // Prices that will run out with nothing queued after them. One rate row = one alert.
    private void rateEndingAlerts(List<AlertResponse> alerts, LocalDate today, int withinDays) {
        for (RateFamilyResponse family : rateService.families()) {
            for (RateRowResponse row : rateService.endingWithoutSuccessor(family.name(), today, today.plusDays(withinDays))) {
                int days = (int) ChronoUnit.DAYS.between(today, row.effectiveTo());
                alerts.add(new AlertResponse(RATE_ENDING_SOON, severityFor(days), "rate:" + family.name(),
                        new RefResponse(row.id(), family.label() + ": " + describe(row)), row.effectiveTo(), days,
                        family.label() + " (" + describe(row) + ") " + whenEnds(days, row.effectiveTo())
                                + " and no later rate is set"));
            }
        }
    }

    // Active things with no rate in force today. dueDate is the day their last rate ended, when there was one.
    private void noCurrentRateAlerts(List<AlertResponse> alerts, LocalDate today, String subjectType, String rateTable,
                                     String keyColumn, String code, String rateWord, String activeCondition) {
        String table = subjectType;
        jdbc.sql("select p.id, p.name, (select max(r.effective_to) from " + rateTable + " r where r." + keyColumn
                        + " = p.id) as last_ended from " + table + " p where " + activeCondition
                        + " and not exists (select 1 from " + rateTable + " r where r." + keyColumn + " = p.id"
                        + " and r.effective_from <= :today and (r.effective_to is null or r.effective_to > :today))"
                        + " order by p.name")
                .param("today", today)
                .query((rs, n) -> {
                    LocalDate lastEnded = rs.getObject("last_ended", LocalDate.class);
                    String name = rs.getString("name");
                    String message = name + " has no " + rateWord + " in force today"
                            + (lastEnded == null ? "" : " (the last one ended on " + lastEnded + ")");
                    alerts.add(new AlertResponse(code, "WARNING", subjectType, new RefResponse(rs.getInt("id"), name),
                            lastEnded, lastEnded == null ? null : (int) ChronoUnit.DAYS.between(today, lastEnded), message));
                    return null;
                }).list();
    }

    // ------------------------------------------------------------------ helpers

    private static int days(Integer withinDays, Map<String, String> errors) {
        int days = withinDays == null ? DEFAULT_WITHIN_DAYS : withinDays;
        if (days < 0 || days > MAX_WITHIN_DAYS) {
            errors.put("withinDays", "must be between 0 and " + MAX_WITHIN_DAYS);
        }
        return days;
    }

    private static String severityFor(int daysLeft) {
        return daysLeft < 0 ? "CRITICAL" : daysLeft <= WARNING_DAYS ? "WARNING" : "INFO";
    }

    private static String when(int days, LocalDate date) {
        if (days < 0) {
            return "expired " + (-days) + (days == -1 ? " day" : " days") + " ago (" + date + ")";
        }
        return days == 0 ? "expires today (" + date + ")" : "expires in " + days + (days == 1 ? " day" : " days") + " (" + date + ")";
    }

    private static String whenEnds(int days, LocalDate date) {
        return days == 0 ? "ends today" : "ends in " + days + (days == 1 ? " day" : " days") + " (" + date + ")";
    }

    // "Hilton Colombo, Deluxe, Peak, 2, all nationalities": the key labels, with an unlabelled key shown by its value
    // and an "applies to all" key by what it covers.
    private static String describe(RateRowResponse row) {
        RateFamilies.Family family = RateFamilies.require(row.family());
        List<String> parts = new ArrayList<>();
        for (RateFamilies.Key key : family.keys()) {
            Object value = row.keys().get(key.name());
            if (value == null) {
                parts.add("all " + key.label().toLowerCase() + "s");
            } else if (row.labels().get(key.name()) != null) {
                parts.add(row.labels().get(key.name()));
            } else {
                parts.add(String.valueOf(value));
            }
        }
        return parts.isEmpty() ? "all" : String.join(", ", parts);
    }
}
