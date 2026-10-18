package lk.serenetravels.masterdataservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

// Omitted (null) fields are left unchanged. effectiveTo cannot be cleared here - to re-open a
// closed rate, set a new rate instead.
public record RateCorrectionRequest(BigDecimal rate, LocalDate effectiveFrom, LocalDate effectiveTo) {
}
