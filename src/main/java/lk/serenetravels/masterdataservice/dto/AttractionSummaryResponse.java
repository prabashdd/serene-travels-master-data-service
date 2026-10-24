package lk.serenetravels.masterdataservice.dto;

import java.util.List;

public record AttractionSummaryResponse(Integer id, String name, RefResponse destination, String pricingMethod,
                                        boolean isSafari, Integer jeepCapacity, Integer fixedGroupSize,
                                        List<AttractionRateAmount> currentRates) {
}
