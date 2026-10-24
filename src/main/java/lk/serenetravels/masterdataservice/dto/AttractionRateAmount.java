package lk.serenetravels.masterdataservice.dto;

import java.math.BigDecimal;

// A price in force today, in the compact form used on list rows.
public record AttractionRateAmount(String rateType, BigDecimal rate, RateUnit unit) {
}
