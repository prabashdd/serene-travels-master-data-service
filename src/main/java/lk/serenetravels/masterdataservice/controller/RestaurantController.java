package lk.serenetravels.masterdataservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.dto.RestaurantResponse;
import lk.serenetravels.masterdataservice.service.RestaurantService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Tag(name = "Restaurants", description = "Meal stops, the days each is closed and the dietary needs it can cater for.")
@RestController
@RequestMapping("/api/v1/master-data")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class RestaurantController {

    private final RestaurantService restaurantService;

    public RestaurantController(RestaurantService restaurantService) {
        this.restaurantService = restaurantService;
    }

    @Operation(summary = "List restaurants",
            description = "Each restaurant comes with its closed days (day names, Monday first) and dietary support. "
                    + "Filter with q (name), destinationId, dietaryCategoryId (restaurants that cater for it), closedOn "
                    + "and openOn (a day name such as MONDAY - openOn keeps restaurants NOT closed that day). Sort with "
                    + "sort=name,asc (repeatable); fields: name, destination, id.")
    @GetMapping("/restaurants")
    public PageResponse<RestaurantResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer destinationId,
            @RequestParam(required = false) Integer dietaryCategoryId,
            @RequestParam(required = false) String closedOn,
            @RequestParam(required = false) String openOn,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "field,direction - repeatable, e.g. sort=destination,asc&sort=name,asc")
            HttpServletRequest request) {
        // Read the raw values: a bound List<String> would split "name,asc" on the comma.
        String[] sort = request.getParameterValues("sort");
        return restaurantService.list(q, destinationId, dietaryCategoryId, closedOn, openOn,
                sort == null ? List.of() : Arrays.asList(sort), page, size);
    }

    @Operation(summary = "One restaurant with its closed days and dietary support")
    @GetMapping("/restaurants/{id}")
    public RestaurantResponse get(@PathVariable int id) {
        return restaurantService.get(id);
    }

    @Operation(summary = "Add a restaurant",
            description = "Required: name (unique within its destination), destinationId. Optional, to set in the same "
                    + "call: closedDays (day names, e.g. [\"MONDAY\", \"TUESDAY\"]; any letter case) and "
                    + "dietaryCategoryIds (ids from the 'dietary-categories' lookup).")
    @PostMapping("/restaurants")
    public ResponseEntity<RestaurantResponse> create(@RequestBody Map<String, Object> body) {
        RestaurantResponse created = restaurantService.create(body);
        return ResponseEntity.created(URI.create("/api/v1/master-data/restaurants/" + created.id())).body(created);
    }

    @Operation(summary = "Quick edit: rename or move a restaurant",
            description = "Send name and/or destinationId. Closed days and dietary support have their own calls.")
    @PatchMapping("/restaurants/{id}")
    public RestaurantResponse update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return restaurantService.update(id, body);
    }

    @Operation(summary = "Delete a restaurant",
            description = "Removes the restaurant together with its closed days and dietary links.")
    @DeleteMapping("/restaurants/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        restaurantService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Replace the days the restaurant is closed",
            description = "Send the complete list of day names (use [] if it is open every day). Returned in week order.")
    @PutMapping("/restaurants/{id}/closed-days")
    public List<String> replaceClosedDays(@PathVariable int id, @RequestBody List<String> days) {
        return restaurantService.replaceClosedDays(id, days);
    }

    @Operation(summary = "Replace the dietary needs the restaurant can cater for",
            description = "Send the complete list of dietary category ids (use [] to clear it).")
    @PutMapping("/restaurants/{id}/dietary")
    public List<RefResponse> replaceDietary(@PathVariable int id, @RequestBody List<Integer> dietaryCategoryIds) {
        return restaurantService.replaceDietary(id, dietaryCategoryIds);
    }
}
