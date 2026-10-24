package lk.serenetravels.masterdataservice.dto;

import java.util.List;

// The whole hotel as one object: details plus everything that hangs off it. `rates` is only filled when
// the caller asks for ?include=rates (it can be large), otherwise it is null.
public record PropertyResponse(Integer id, String code, String name, boolean active, Integer starRating,
                               String address, String website, String email, String phone,
                               RefResponse destination, List<RoomTypeResponse> roomTypes,
                               List<ChildRuleResponse> childRules, List<PropertyInclusionResponse> inclusions,
                               BabyCotResponse babyCot, List<DietarySupportResponse> dietary,
                               List<RateRowResponse> rates) {
}
