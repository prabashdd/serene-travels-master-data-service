package lk.serenetravels.masterdataservice.dto;

// locationId is what the tuk-tuk rate family is keyed by; currentFare is null until a fare is set.
public record TukTukResponse(Integer locationId, RateAmount currentFare) {
}
