package lk.serenetravels.masterdataservice.dto;

import java.math.BigDecimal;

// `matched` is EXACT, or FALLBACK_<ALL_...> when the row for "applies to all" (e.g. all
// nationalities) was used because no row existed for the specific key.
public record ResolvedRateResponse(BigDecimal rate, RateUnit unit, String matched, RateRowResponse row) {
}
