package lk.serenetravels.masterdataservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lk.serenetravels.masterdataservice.dto.LocationDistanceResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.service.LocationDistanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Tag(name = "Distances & Articles", description = "Road distance and driving time between destinations.")
@RestController
@RequestMapping("/api/v1/master-data")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class LocationDistanceController {

    private final LocationDistanceService distanceService;

    public LocationDistanceController(LocationDistanceService distanceService) {
        this.distanceService = distanceService;
    }

    @Operation(summary = "List distances between destinations",
            description = "Filter with q (either destination's name), destinationId (either end), fromDestinationId, "
                    + "toDestinationId, routeType and needsReview=true (suspect rows: a destination to itself, or a distance "
                    + "that is not above zero). Sort with sort=distanceKm,desc (repeatable); fields: from, to, routeType, "
                    + "distanceKm, durationMinutes, id. To ask \"how far is A from B\" use /location-distances/between, "
                    + "because most pairs are stored in one direction only.")
    @GetMapping("/location-distances")
    public PageResponse<LocationDistanceResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer fromDestinationId,
            @RequestParam(required = false) Integer toDestinationId,
            @RequestParam(required = false) Integer destinationId,
            @RequestParam(required = false) String routeType,
            @RequestParam(required = false) Boolean needsReview,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "field,direction - repeatable, e.g. sort=from,asc&sort=distanceKm,asc")
            HttpServletRequest request) {
        // Read the raw values: a bound List<String> would split "name,asc" on the comma.
        String[] sort = request.getParameterValues("sort");
        return distanceService.list(q, fromDestinationId, toDestinationId, destinationId, routeType, needsReview,
                sort == null ? List.of() : Arrays.asList(sort), page, size);
    }

    @Operation(summary = "Every route between two destinations, whichever way round it is stored",
            description = "Returns each route option (ROAD, ROADA, ROADB ...) shortest first, with from/to as stored. "
                    + "An empty list means no distance is recorded for the pair.")
    @GetMapping("/location-distances/between")
    public List<LocationDistanceResponse> between(@RequestParam int fromDestinationId, @RequestParam int toDestinationId) {
        return distanceService.between(fromDestinationId, toDestinationId);
    }

    @Operation(summary = "One distance")
    @GetMapping("/location-distances/{id}")
    public LocationDistanceResponse get(@PathVariable int id) {
        return distanceService.get(id);
    }

    @Operation(summary = "Add a distance",
            description = "Required: fromDestinationId, toDestinationId (different), routeType (e.g. ROAD, ROADA, ROADB), "
                    + "distanceKm (above zero, one decimal place). Optional: durationMinutes. The same two destinations with "
                    + "the same route type, in either direction, can only be recorded once (409 DUPLICATE).")
    @PostMapping("/location-distances")
    public ResponseEntity<LocationDistanceResponse> create(@RequestBody Map<String, Object> body) {
        LocationDistanceResponse created = distanceService.create(body);
        return ResponseEntity.created(URI.create("/api/v1/master-data/location-distances/" + created.id())).body(created);
    }

    @Operation(summary = "Quick edit: send only the fields that changed",
            description = "Send null for durationMinutes to clear it. The row as it will look must be valid, so fixing a "
                    + "row flagged needsReview means sending the corrected field(s) in the same call.")
    @PatchMapping("/location-distances/{id}")
    public LocationDistanceResponse update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return distanceService.update(id, body);
    }

    @Operation(summary = "Delete a distance")
    @DeleteMapping("/location-distances/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        distanceService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
