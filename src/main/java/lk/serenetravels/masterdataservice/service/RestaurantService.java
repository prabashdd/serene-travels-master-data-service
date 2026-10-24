package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.dto.RestaurantResponse;

import java.util.List;
import java.util.Map;

// Restaurants (meal stops), the days each is closed and the dietary needs it can cater for. Days are exchanged as
// names (MONDAY ... SUNDAY); the numeric 0 = Sunday .. 6 = Saturday storage is an internal detail.
public interface RestaurantService {

    // `sort` entries are "field,direction" (fields: name, destination, id). closedOn / openOn take a day name.
    PageResponse<RestaurantResponse> list(String q, Integer destinationId, Integer dietaryCategoryId, String closedOn,
                                          String openOn, List<String> sort, int page, int size);

    RestaurantResponse get(int id);

    // The body may carry closedDays (day names) and dietaryCategoryIds to set both in the same call.
    RestaurantResponse create(Map<String, Object> body);

    // Partial update of name / destinationId. Closed days and dietary support have their own calls.
    RestaurantResponse update(int id, Map<String, Object> body);

    // Hard delete (restaurants have no active flag); also removes its closed days and dietary links.
    void delete(int id);

    // REPLACES the whole set of closed days; returns them in week order.
    List<String> replaceClosedDays(int restaurantId, List<String> days);

    // REPLACES the whole set of dietary categories the restaurant supports.
    List<RefResponse> replaceDietary(int restaurantId, List<Integer> dietaryCategoryIds);
}
