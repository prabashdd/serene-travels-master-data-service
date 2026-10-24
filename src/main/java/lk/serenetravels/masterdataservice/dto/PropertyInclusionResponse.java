package lk.serenetravels.masterdataservice.dto;

import java.math.BigDecimal;

// cost null = included free of charge.
public record PropertyInclusionResponse(RefResponse inclusion, BigDecimal cost) {
}
