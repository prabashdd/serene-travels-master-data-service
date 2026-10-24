package lk.serenetravels.masterdataservice.dto;

import java.time.LocalDate;

// A promotion applies from validFrom up to but not including validTo. A null destination or season means it is not
// limited to one. status is worked out for the requested date: INACTIVE, UPCOMING, RUNNING or ENDED. currentValue is
// the value in force on that date (a percentage for PERCENTAGE promotions, USD otherwise), or null when none is set.
public record PromotionResponse(Integer id, String name, String type, LocalDate validFrom, LocalDate validTo,
                                Integer minGroupSize, RefResponse destination, RefResponse season, boolean active,
                                String status, RateAmount currentValue) {
}
