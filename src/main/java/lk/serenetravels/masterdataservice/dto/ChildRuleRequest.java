package lk.serenetravels.masterdataservice.dto;

import java.math.BigDecimal;

// Fields are nullable on purpose: the service validates them and reports every problem, per item, as a field error.
public record ChildRuleRequest(Integer minAge, Integer maxAge, Boolean requiresBed, BigDecimal cost) {
}
