package lk.serenetravels.masterdataservice.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

// `labels` carries display names for the key fields so a generic rate grid needs no client-side joins;
// `isCurrent` is computed against the requested date so the FE never does date maths.
public record RateRowResponse(Integer id, String family, Map<String, Object> keys, Map<String, String> labels,
                              BigDecimal rate, RateUnit unit, LocalDate effectiveFrom, LocalDate effectiveTo,
                              boolean isCurrent, Instant createdAt, String createdBy) {
}
