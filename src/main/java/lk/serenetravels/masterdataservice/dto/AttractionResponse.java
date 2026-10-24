package lk.serenetravels.masterdataservice.dto;

import java.util.List;

// tukTuk is null when the attraction has no tuk-tuk option.
public record AttractionResponse(Integer id, String name, RefResponse destination, String pricingMethod,
                                 boolean isSafari, Integer jeepCapacity, Integer fixedGroupSize,
                                 List<RateRowResponse> currentRates, TukTukResponse tukTuk) {
}
