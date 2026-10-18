package lk.serenetravels.masterdataservice.dto;

import java.math.BigDecimal;
import java.util.Map;

public record RateAdjustItem(Map<String, Object> keys, BigDecimal oldRate, BigDecimal newRate, RateUnit unit) {
}
