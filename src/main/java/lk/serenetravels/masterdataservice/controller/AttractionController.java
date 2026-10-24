package lk.serenetravels.masterdataservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lk.serenetravels.masterdataservice.dto.AttractionResponse;
import lk.serenetravels.masterdataservice.dto.AttractionSummaryResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.TukTukResponse;
import lk.serenetravels.masterdataservice.service.AttractionService;
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

@Tag(name = "Attractions", description = "Priced sites, activities and charges. Prices are edited through the "
        + "Rates endpoints (families 'attraction' and 'tuk-tuk').")
@RestController
@RequestMapping("/api/v1/master-data")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class AttractionController {

    private final AttractionService attractionService;

    public AttractionController(AttractionService attractionService) {
        this.attractionService = attractionService;
    }

    @Operation(summary = "List attractions",
            description = "One row per attraction with the prices in force today. Filter with q (name), destinationId, "
                    + "safari and pricingMethod (PER_PERSON or FIXED_GROUP_PLUS_EXTRA). Sort with sort=name,asc "
                    + "(repeatable); allowed fields: name, destination, pricingMethod, id.")
    @GetMapping("/attractions")
    public PageResponse<AttractionSummaryResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer destinationId,
            @RequestParam(required = false) Boolean safari,
            @RequestParam(required = false) String pricingMethod,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "field,direction - repeatable, e.g. sort=destination,asc&sort=name,asc")
            HttpServletRequest request) {
        // Read the raw values: a bound List<String> would split "name,asc" on the comma.
        String[] sort = request.getParameterValues("sort");
        return attractionService.list(q, destinationId, safari, pricingMethod,
                sort == null ? List.of() : Arrays.asList(sort), page, size);
    }

    @Operation(summary = "One attraction with its current prices and tuk-tuk option",
            description = "'at' evaluates the prices as of that date (default today).")
    @GetMapping("/attractions/{id}")
    public AttractionResponse get(@PathVariable int id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate at) {
        return attractionService.get(id, at);
    }

    @Operation(summary = "Create an attraction",
            description = "Required: name (unique within its destination), destinationId, pricingMethod "
                    + "(PER_PERSON or FIXED_GROUP_PLUS_EXTRA). Optional: isSafari (default false), jeepCapacity (1-50), "
                    + "fixedGroupSize (required for FIXED_GROUP_PLUS_EXTRA, not allowed for PER_PERSON).")
    @PostMapping("/attractions")
    public ResponseEntity<AttractionResponse> create(@RequestBody Map<String, Object> body) {
        AttractionResponse created = attractionService.create(body);
        return ResponseEntity.created(URI.create("/api/v1/master-data/attractions/" + created.id())).body(created);
    }

    @Operation(summary = "Quick edit: send only the fields that changed",
            description = "Send null to clear jeepCapacity or fixedGroupSize. The result must still be valid, e.g. "
                    + "switching to FIXED_GROUP_PLUS_EXTRA needs a fixedGroupSize in the same request or already set.")
    @PatchMapping("/attractions/{id}")
    public AttractionResponse update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return attractionService.update(id, body);
    }

    @Operation(summary = "Delete an attraction",
            description = "409 IN_USE while it has rates, a tuk-tuk option or belongs to a package.")
    @DeleteMapping("/attractions/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        attractionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Give the attraction a tuk-tuk option",
            description = "Idempotent. The returned locationId is the key for tuk-tuk fares "
                    + "(PUT /rates/tuk-tuk with keys.tukTukLocationId).")
    @PutMapping("/attractions/{id}/tuk-tuk")
    public TukTukResponse enableTukTuk(@PathVariable int id) {
        return attractionService.enableTukTuk(id);
    }

    @Operation(summary = "Remove the tuk-tuk option", description = "409 IN_USE while fares exist for it.")
    @DeleteMapping("/attractions/{id}/tuk-tuk")
    public ResponseEntity<Void> disableTukTuk(@PathVariable int id) {
        attractionService.disableTukTuk(id);
        return ResponseEntity.noContent().build();
    }
}
