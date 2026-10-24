package lk.serenetravels.masterdataservice.dto;

import java.math.BigDecimal;

// One road option between two destinations, as stored (from -> to). durationMinutes is null when unknown.
// needsReview flags a row that cannot be right - a destination to itself, or a distance that is not above zero -
// so the FE can offer a "fix these" view.
public record LocationDistanceResponse(Integer id, RefResponse from, RefResponse to, String routeType,
                                       BigDecimal distanceKm, Integer durationMinutes, boolean needsReview) {
}
