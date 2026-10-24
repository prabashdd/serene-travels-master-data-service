package lk.serenetravels.masterdataservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.service.PropertyService;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Tag(name = "Properties", description = "Hotels: the hotel record and everything attached to it. "
        + "Room prices are edited through the Rates endpoints (family 'property').")
@RestController
@RequestMapping("/api/v1/master-data")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class PropertyController {

    private final PropertyService propertyService;

    public PropertyController(PropertyService propertyService) {
        this.propertyService = propertyService;
    }

    @Operation(summary = "List hotels",
            description = "One slim row per hotel, with its cheapest current rate. Filter with q (name or code), "
                    + "destinationId, active and starRating. Sort with sort=name,asc (repeatable); allowed fields: "
                    + "name, code, starRating, destination, id.")
    @GetMapping("/properties")
    public PageResponse<PropertySummaryResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer destinationId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Integer starRating,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "field,direction - repeatable, e.g. sort=starRating,desc&sort=name,asc")
            HttpServletRequest request) {
        // Read the raw values: a bound List<String> would split "name,asc" on the comma.
        String[] sort = request.getParameterValues("sort");
        return propertyService.list(q, destinationId, active, starRating,
                sort == null ? List.of() : Arrays.asList(sort), page, size);
    }

    @Operation(summary = "One hotel with room types, child rules, inclusions, baby cot and dietary support",
            description = "Add include=rates to also get every rate in force on 'at' (default today).")
    @GetMapping("/properties/{id}")
    public PropertyResponse get(@PathVariable int id,
            @RequestParam(required = false) List<String> include,
            @Parameter(description = "Evaluate included rates as of this date (default today)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate at) {
        boolean includeRates = false;
        if (include != null) {
            for (String part : include) {
                if (!part.equals("rates")) {
                    throw InvalidRequestException.field("include", "'" + part + "' is not supported; use 'rates'");
                }
                includeRates = true;
            }
        }
        return propertyService.get(id, includeRates, at);
    }

    @Operation(summary = "Create a hotel",
            description = "Required: code (unique), name, destinationId. Optional: address, starRating (1-5), "
                    + "website, email, phone, active (default true).")
    @PostMapping("/properties")
    public ResponseEntity<PropertyResponse> create(@RequestBody Map<String, Object> body) {
        PropertyResponse created = propertyService.create(body);
        return ResponseEntity.created(URI.create("/api/v1/master-data/properties/" + created.id())).body(created);
    }

    @Operation(summary = "Quick edit: send only the fields that changed",
            description = "Send null to clear an optional field. To bring back a deactivated hotel send {\"active\": true}.")
    @PatchMapping("/properties/{id}")
    public PropertyResponse update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return propertyService.update(id, body);
    }

    @Operation(summary = "Deactivate a hotel (soft delete)",
            description = "Sets active=false; the hotel and its history stay in the database.")
    @DeleteMapping("/properties/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable int id) {
        propertyService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Add a room type to a hotel",
            description = "Required: name (unique within the hotel). Optional: baseCapacity (1-30), extraBedAllowed.")
    @PostMapping("/properties/{id}/room-types")
    public ResponseEntity<RoomTypeResponse> addRoomType(@PathVariable int id, @RequestBody Map<String, Object> body) {
        RoomTypeResponse created = propertyService.addRoomType(id, body);
        return ResponseEntity.created(URI.create("/api/v1/master-data/room-types/" + created.id())).body(created);
    }

    @Operation(summary = "Edit a room type (send only the changed fields)")
    @PatchMapping("/room-types/{id}")
    public RoomTypeResponse updateRoomType(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return propertyService.updateRoomType(id, body);
    }

    @Operation(summary = "Delete a room type", description = "409 IN_USE while rates still reference it.")
    @DeleteMapping("/room-types/{id}")
    public ResponseEntity<Void> deleteRoomType(@PathVariable int id) {
        propertyService.deleteRoomType(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Replace the hotel's child pricing rules",
            description = "Send the complete list (use [] to clear it). Age bands must not overlap; ages are 0-17.")
    @PutMapping("/properties/{id}/child-rules")
    public List<ChildRuleResponse> replaceChildRules(@PathVariable int id, @RequestBody List<ChildRuleRequest> rules) {
        return propertyService.replaceChildRules(id, rules);
    }

    @Operation(summary = "Replace the hotel's inclusions",
            description = "Send the complete list. cost null means included free of charge.")
    @PutMapping("/properties/{id}/inclusions")
    public List<PropertyInclusionResponse> replaceInclusions(@PathVariable int id,
            @RequestBody List<PropertyInclusionRequest> items) {
        return propertyService.replaceInclusions(id, items);
    }

    @Operation(summary = "Replace the hotel's dietary support",
            description = "Send the complete list of dietary categories with supported true/false.")
    @PutMapping("/properties/{id}/dietary")
    public List<DietarySupportResponse> replaceDietary(@PathVariable int id,
            @RequestBody List<DietarySupportRequest> items) {
        return propertyService.replaceDietary(id, items);
    }

    @Operation(summary = "Set the hotel's baby cot option",
            description = "available=false clears the cost.")
    @PutMapping("/properties/{id}/baby-cot")
    public BabyCotResponse setBabyCot(@PathVariable int id, @RequestBody BabyCotRequest request) {
        return propertyService.setBabyCot(id, request);
    }
}
