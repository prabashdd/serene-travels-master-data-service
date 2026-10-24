package lk.serenetravels.masterdataservice.dto;

import java.util.List;

// closedDays are full day names in week order (MONDAY first); dietary lists the dietary needs the restaurant can cater for.
public record RestaurantResponse(Integer id, String name, RefResponse destination, List<String> closedDays,
                                 List<RefResponse> dietary) {
}
