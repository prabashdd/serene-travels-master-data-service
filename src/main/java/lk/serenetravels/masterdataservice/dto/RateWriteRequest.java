package lk.serenetravels.masterdataservice.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record RateWriteRequest(
        @NotNull(message = "keys are required") Map<String, Object> keys,
        @NotNull(message = "rate is required") BigDecimal rate,
        LocalDate effectiveFrom) {
}
