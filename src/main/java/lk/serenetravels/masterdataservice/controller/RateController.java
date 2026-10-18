package lk.serenetravels.masterdataservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RateAdjustItem;
import lk.serenetravels.masterdataservice.dto.RateAdjustRequest;
import lk.serenetravels.masterdataservice.dto.RateCorrectionRequest;
import lk.serenetravels.masterdataservice.dto.RateFamilyResponse;
import lk.serenetravels.masterdataservice.dto.RateRowResponse;
import lk.serenetravels.masterdataservice.dto.RateWriteRequest;
import lk.serenetravels.masterdataservice.dto.ResolvedRateResponse;
import lk.serenetravels.masterdataservice.service.RateService;
import lk.serenetravels.masterdataservice.util.CurrentUser;
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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Rates", description = "One engine for all price tables. A change closes the open row and "
        + "inserts a new one; history is never rewritten.")
@RestController
@RequestMapping("/api/v1/master-data")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class RateController {

    private final RateService rateService;

    public RateController(RateService rateService) {
        this.rateService = rateService;
    }

    @Operation(summary = "Rate family registry (keys, unit, nullable-key meaning) - drives generic rate UIs")
    @GetMapping("/rates/families")
    public List<RateFamilyResponse> families() {
        return rateService.families();
    }

    // Method-level @PreAuthorize replaces the class-level one: the future quotation service calls this
    // with its own service-account token, and is allowed nothing else.
    @Operation(summary = "Resolve the single applicable price (used by the quotation service)",
            description = "Pass the family's keys as keys[name]=value, e.g. keys[propertyId]=42&keys[roomTypeId]=1"
                    + "&keys[seasonId]=1&keys[occupancy]=2&keys[nationalityId]=9. Falls back to the 'applies to "
                    + "all' row; 404 NO_RATE if neither exists.")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','QUOTATION_SERVICE')")
    @GetMapping("/rates/resolve")
    public ResolvedRateResponse resolve(
            @RequestParam String family,
            @Parameter(description = "Evaluate as of this date (default today)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate at,
            @Parameter(hidden = true) @RequestParam Map<String, String> allParams) {
        return rateService.resolve(family, nested(allParams, "keys"), at);
    }

    @Operation(summary = "Rates for a subset of keys",
            description = "Filter with filter[propertyId]=42&filter[seasonId]=1 (filter[nationalityId]=null "
                    + "selects the 'all nationalities' row). history=true includes closed rows (the audit view).")
    @GetMapping("/rates/{family}")
    public PageResponse<RateRowResponse> list(
            @PathVariable String family,
            @Parameter(description = "Evaluate 'current' as of this date (default today)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate at,
            @RequestParam(defaultValue = "false") boolean history,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(hidden = true) @RequestParam Map<String, String> allParams) {
        return rateService.list(family, nested(allParams, "filter"), at, history, page, size);
    }

    @Operation(summary = "Set a rate (closes the open row, inserts a new one)",
            description = "effectiveFrom defaults to today. 409 RATE_STARTS_BEFORE_CURRENT if it is not after the "
                    + "current row's start; 409 FUTURE_RATE_EXISTS if a pending future row exists. For the vehicle "
                    + "family, omit keys.seasonId to write every season in one transaction.")
    @PutMapping("/rates/{family}")
    public List<RateRowResponse> setRate(@PathVariable String family, @Valid @RequestBody RateWriteRequest request) {
        return rateService.setRate(family, request, CurrentUser.username());
    }

    @Operation(summary = "Correct a row in place (typo fix)")
    @PatchMapping("/rates/{family}/{id}")
    public RateRowResponse correct(@PathVariable String family, @PathVariable int id,
            @RequestBody RateCorrectionRequest request) {
        return rateService.correct(family, id, request);
    }

    @Operation(summary = "Delete a pending future-dated row (history is never deleted)")
    @DeleteMapping("/rates/{family}/{id}")
    public ResponseEntity<Void> deletePending(@PathVariable String family, @PathVariable int id) {
        rateService.deletePending(family, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Bulk percent/amount change with preview",
            description = "dryRun=true returns old -> new for every affected row and writes nothing; "
                    + "dryRun=false applies it atomically through the same rule as PUT.")
    @PostMapping("/rates/{family}/adjust")
    public List<RateAdjustItem> adjust(@PathVariable String family, @Valid @RequestBody RateAdjustRequest request) {
        return rateService.adjust(family, request, CurrentUser.username());
    }

    @Operation(summary = "Save a property's rate grid atomically",
            description = "Each item follows the PUT /rates/property rule; the whole request is all-or-nothing.")
    @PutMapping("/properties/{id}/rates")
    public List<RateRowResponse> saveGrid(@PathVariable int id, @Valid @RequestBody List<@Valid RateWriteRequest> items) {
        return rateService.saveGrid(id, items, CurrentUser.username());
    }

    // Pulls name[key]=value pairs out of the flat query-string map: filter[propertyId]=42 -> {propertyId: 42}.
    private Map<String, String> nested(Map<String, String> params, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        String start = prefix + "[";
        params.forEach((name, value) -> {
            if (name.startsWith(start) && name.endsWith("]")) {
                result.put(name.substring(start.length(), name.length() - 1), value);
            }
        });
        return result;
    }
}
