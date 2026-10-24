package lk.serenetravels.masterdataservice.dto;

// The slim row used in lists. fromRate is the cheapest rate in force today (null if the hotel has none yet).
public record PropertySummaryResponse(Integer id, String code, String name, boolean active, Integer starRating,
                                      RefResponse destination, int roomTypeCount, RateAmount fromRate) {
}
