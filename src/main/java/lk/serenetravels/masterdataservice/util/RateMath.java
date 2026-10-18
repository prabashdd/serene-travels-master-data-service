package lk.serenetravels.masterdataservice.util;

import lk.serenetravels.masterdataservice.dto.RateAdjustRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class RateMath {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private RateMath() {
    }

    // Applies a bulk change to a rate: PERCENT scales it, AMOUNT shifts it. The result always
    // lands on a whole cent (the columns are numeric(_,2)); NEAREST_1 rounds to a whole unit.
    public static BigDecimal adjust(BigDecimal oldRate, RateAdjustRequest.Mode mode, BigDecimal value,
                                    RateAdjustRequest.Rounding rounding) {
        BigDecimal raw = switch (mode) {
            case PERCENT -> oldRate.multiply(HUNDRED.add(value)).divide(HUNDRED, 6, RoundingMode.HALF_UP);
            case AMOUNT -> oldRate.add(value);
        };
        int scale = rounding == RateAdjustRequest.Rounding.NEAREST_1 ? 0 : 2;
        return raw.setScale(scale, RoundingMode.HALF_UP).setScale(2, RoundingMode.UNNECESSARY);
    }
}
