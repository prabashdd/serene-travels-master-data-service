package lk.serenetravels.masterdataservice.dto;

import java.math.BigDecimal;

public record RateAmount(BigDecimal rate, RateUnit unit) {
}
