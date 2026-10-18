package lk.serenetravels.masterdataservice.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record RateAdjustRequest(
        // Key name -> one id, or an array of ids. An empty filter means every current row of the family.
        @NotNull(message = "filter is required (use {} for all rows)") Map<String, Object> filter,
        @NotNull(message = "mode is required") Mode mode,
        // Signed: 8 = +8% (or +8.00), -5 = -5%.
        @NotNull(message = "value is required") BigDecimal value,
        Rounding rounding,
        LocalDate effectiveFrom,
        @NotNull(message = "dryRun is required") Boolean dryRun) {

    public enum Mode {
        PERCENT,
        AMOUNT
    }

    public enum Rounding {
        NEAREST_0_01,
        NEAREST_1
    }
}
