package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.dto.BabyCotRequest;
import lk.serenetravels.masterdataservice.dto.BabyCotResponse;
import lk.serenetravels.masterdataservice.dto.ChildRuleRequest;
import lk.serenetravels.masterdataservice.dto.ChildRuleResponse;
import lk.serenetravels.masterdataservice.dto.DietarySupportRequest;
import lk.serenetravels.masterdataservice.dto.DietarySupportResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.PropertyInclusionRequest;
import lk.serenetravels.masterdataservice.dto.PropertyInclusionResponse;
import lk.serenetravels.masterdataservice.dto.PropertyResponse;
import lk.serenetravels.masterdataservice.dto.PropertySummaryResponse;
import lk.serenetravels.masterdataservice.dto.RoomTypeResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// A hotel as one aggregate: the property row plus its room types, child rules, inclusions, baby cot and
// dietary support. Prices are NOT edited here - they go through the rate engine (family "property").
public interface PropertyService {

    // `sort` entries are "field,direction" (fields: name, code, starRating, destination, id).
    PageResponse<PropertySummaryResponse> list(String q, Integer destinationId, Boolean active, Integer starRating,
                                               List<String> sort, int page, int size);

    // includeRates adds every rate in force on `at` (default today).
    PropertyResponse get(int id, boolean includeRates, LocalDate at);

    PropertyResponse create(Map<String, Object> body);

    // Partial update: only the fields present in the body change.
    PropertyResponse update(int id, Map<String, Object> body);

    // Soft delete (active = false). Reactivate with update({"active": true}).
    void deactivate(int id);

    RoomTypeResponse addRoomType(int propertyId, Map<String, Object> body);

    RoomTypeResponse updateRoomType(int roomTypeId, Map<String, Object> body);

    // 409 IN_USE while rates still reference the room type.
    void deleteRoomType(int roomTypeId);

    // The next four REPLACE the whole set, so the FE just sends the list it is showing.
    List<ChildRuleResponse> replaceChildRules(int propertyId, List<ChildRuleRequest> rules);

    List<PropertyInclusionResponse> replaceInclusions(int propertyId, List<PropertyInclusionRequest> items);

    List<DietarySupportResponse> replaceDietary(int propertyId, List<DietarySupportRequest> items);

    BabyCotResponse setBabyCot(int propertyId, BabyCotRequest request);
}
