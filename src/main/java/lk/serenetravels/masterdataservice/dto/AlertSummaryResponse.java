package lk.serenetravels.masterdataservice.dto;

import java.util.Map;

// Counts for a dashboard badge. Every severity and every alert code is present, with 0 when there are none,
// so the shape never changes.
public record AlertSummaryResponse(int total, Map<String, Integer> bySeverity, Map<String, Integer> byCode) {
}
