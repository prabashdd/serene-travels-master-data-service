package lk.serenetravels.masterdataservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lk.serenetravels.masterdataservice.dto.AlertResponse;
import lk.serenetravels.masterdataservice.dto.AlertSummaryResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.service.AlertService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Operations", description = "Alerts, import and export.")
@RestController
@RequestMapping("/api/v1/master-data")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @Operation(summary = "Things needing attention, most urgent first",
            description = "Worked out from the current data on every call. Covers: vehicle insurance and revenue licence "
                    + "expiry (VEHICLE_INSURANCE_EXPIRING, VEHICLE_LICENSE_EXPIRING), vehicles with no or an inactive driver "
                    + "(VEHICLE_NO_DRIVER, VEHICLE_DRIVER_INACTIVE, VEHICLE_ASSISTANT_INACTIVE), rates that run out with nothing "
                    + "after them (RATE_ENDING_SOON), and active properties and attractions with no rate in force today "
                    + "(PROPERTY_NO_CURRENT_RATE, ATTRACTION_NO_CURRENT_RATE). Severity: CRITICAL = already expired, WARNING = "
                    + "within 7 days or a missing driver/rate, INFO = further ahead. withinDays (default 30) is how far ahead a "
                    + "coming expiry counts; anything already expired always counts. Filter with code and severity.")
    @GetMapping("/alerts")
    public PageResponse<AlertResponse> list(
            @RequestParam(required = false) Integer withinDays,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return alertService.list(withinDays, code, severity, page, size);
    }

    @Operation(summary = "Alert counts by severity and by code",
            description = "For a dashboard badge. Every severity and code is present, with 0 when there are none.")
    @GetMapping("/alerts/summary")
    public AlertSummaryResponse summary(@RequestParam(required = false) Integer withinDays) {
        return alertService.summary(withinDays);
    }
}
