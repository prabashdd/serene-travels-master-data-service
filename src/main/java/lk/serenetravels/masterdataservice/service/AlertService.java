package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.dto.AlertResponse;
import lk.serenetravels.masterdataservice.dto.AlertSummaryResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;

// "What needs attention" across the master data, worked out from the current data each time: vehicle insurance and
// revenue licence expiry, vehicles without a (working) driver, rates about to run out, and properties and attractions
// with no rate in force today. Read-only.
public interface AlertService {

    // Alerts, most urgent first (CRITICAL, WARNING, INFO, then the nearest date). `withinDays` (default 30) is how far
    // ahead a coming expiry counts; anything already expired always counts. Optional filters: code, severity.
    PageResponse<AlertResponse> list(Integer withinDays, String code, String severity, int page, int size);

    AlertSummaryResponse summary(Integer withinDays);
}
