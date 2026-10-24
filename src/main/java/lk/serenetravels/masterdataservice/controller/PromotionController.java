package lk.serenetravels.masterdataservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.PromotionResponse;
import lk.serenetravels.masterdataservice.service.PromotionService;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Tag(name = "Promotions", description = "Discounts with a type, a validity window and optional limits. The amount is a rate.")
@RestController
@RequestMapping("/api/v1/master-data")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class PromotionController {

    private final PromotionService promotionService;

    public PromotionController(PromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @Operation(summary = "List promotions",
            description = "Each promotion comes with a status (INACTIVE, UPCOMING, RUNNING or ENDED) and its value in force "
                    + "today (or on 'at'). Filter with q (name), type, active, validOn (promotions running on that date), "
                    + "destinationId and seasonId. Sort with sort=validFrom,desc (repeatable); fields: name, type, "
                    + "validFrom, validTo, id. Default: newest first.")
    @GetMapping("/promotions")
    public PageResponse<PromotionResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validOn,
            @RequestParam(required = false) Integer destinationId,
            @RequestParam(required = false) Integer seasonId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate at,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "field,direction - repeatable, e.g. sort=validFrom,desc&sort=name,asc")
            HttpServletRequest request) {
        // Read the raw values: a bound List<String> would split "name,asc" on the comma.
        String[] sort = request.getParameterValues("sort");
        return promotionService.list(q, type, active, validOn, destinationId, seasonId, at,
                sort == null ? List.of() : Arrays.asList(sort), page, size);
    }

    @Operation(summary = "One promotion with its status and current value",
            description = "'at' evaluates the status and value as of that date (default today).")
    @GetMapping("/promotions/{id}")
    public PromotionResponse get(@PathVariable int id,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate at) {
        return promotionService.get(id, at);
    }

    @Operation(summary = "Add a promotion",
            description = "Required: name, type (PERCENTAGE, FIXED_AMOUNT or COMPLIMENTARY), validFrom, validTo (the "
                    + "promotion runs up to, not including, validTo). Optional: minGroupSize, destinationId, seasonId "
                    + "(leave out to apply everywhere), active (default true). Set the value afterwards with PUT /rates/promotion.")
    @PostMapping("/promotions")
    public ResponseEntity<PromotionResponse> create(@RequestBody Map<String, Object> body) {
        PromotionResponse created = promotionService.create(body);
        return ResponseEntity.created(URI.create("/api/v1/master-data/promotions/" + created.id())).body(created);
    }

    @Operation(summary = "Quick edit: send only the fields that changed",
            description = "Send null for minGroupSize, destinationId or seasonId to clear it. The type cannot change once "
                    + "a value has been set (409 TYPE_LOCKED). Bring back a deactivated promotion with {\"active\": true}.")
    @PatchMapping("/promotions/{id}")
    public PromotionResponse update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return promotionService.update(id, body);
    }

    @Operation(summary = "Deactivate a promotion",
            description = "Sets active=false; the promotion and its value history stay.")
    @DeleteMapping("/promotions/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable int id) {
        promotionService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
