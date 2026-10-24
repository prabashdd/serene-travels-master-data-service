package lk.serenetravels.masterdataservice.dto;

import java.math.BigDecimal;

public record ChildRuleResponse(Integer id, int minAge, int maxAge, boolean requiresBed, BigDecimal cost) {
}
