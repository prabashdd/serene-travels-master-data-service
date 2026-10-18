package lk.serenetravels.masterdataservice.util;

import lk.serenetravels.masterdataservice.dto.RateAdjustRequest.Mode;
import lk.serenetravels.masterdataservice.dto.RateAdjustRequest.Rounding;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RateMathTest {

    @Test
    void percentIncreaseRoundsToWholeCent() {
        // 70.00 +8% = 75.60
        assertThat(RateMath.adjust(new BigDecimal("70.00"), Mode.PERCENT, new BigDecimal("8"), Rounding.NEAREST_0_01))
                .isEqualByComparingTo("75.60");
        // 108.00 +3.33% = 111.5964 -> 111.60
        assertThat(RateMath.adjust(new BigDecimal("108.00"), Mode.PERCENT, new BigDecimal("3.33"),
                Rounding.NEAREST_0_01)).isEqualByComparingTo("111.60");
    }

    @Test
    void negativePercentDecreases() {
        assertThat(RateMath.adjust(new BigDecimal("120.00"), Mode.PERCENT, new BigDecimal("-5"), Rounding.NEAREST_0_01))
                .isEqualByComparingTo("114.00");
    }

    @Test
    void amountShiftsTheRate() {
        assertThat(RateMath.adjust(new BigDecimal("150.00"), Mode.AMOUNT, new BigDecimal("-12.5"),
                Rounding.NEAREST_0_01)).isEqualByComparingTo("137.50");
    }

    @Test
    void nearestWholeUnitRoundsHalfUp() {
        assertThat(RateMath.adjust(new BigDecimal("70.00"), Mode.PERCENT, new BigDecimal("8"), Rounding.NEAREST_1))
                .isEqualByComparingTo("76.00");
        assertThat(RateMath.adjust(new BigDecimal("114.50"), Mode.AMOUNT, BigDecimal.ZERO, Rounding.NEAREST_1))
                .isEqualByComparingTo("115.00");
    }

    @Test
    void resultAlwaysHasTwoDecimalPlaces() {
        assertThat(RateMath.adjust(new BigDecimal("10"), Mode.PERCENT, new BigDecimal("10"), Rounding.NEAREST_1).scale())
                .isEqualTo(2);
    }
}
