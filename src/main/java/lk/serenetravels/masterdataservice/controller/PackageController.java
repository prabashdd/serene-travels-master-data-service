package lk.serenetravels.masterdataservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lk.serenetravels.masterdataservice.dto.PackageResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.service.PackageService;
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

@Tag(name = "Packages", description = "Site tour packages: a named bundle of attractions with one price.")
@RestController
@RequestMapping("/api/v1/master-data")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class PackageController {

    private final PackageService packageService;

    public PackageController(PackageService packageService) {
        this.packageService = packageService;
    }

    @Operation(summary = "List site tour packages",
            description = "Each package comes with the attractions it includes and its price in force today (or on 'at'). "
                    + "Filter with q (name) and attractionId (packages that include that site). Sort with "
                    + "sort=name,asc (repeatable); fields: name, id.")
    @GetMapping("/packages")
    public PageResponse<PackageResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer attractionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate at,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "field,direction - repeatable, e.g. sort=name,desc")
            HttpServletRequest request) {
        // Read the raw values: a bound List<String> would split "name,asc" on the comma.
        String[] sort = request.getParameterValues("sort");
        return packageService.list(q, attractionId, at, sort == null ? List.of() : Arrays.asList(sort), page, size);
    }

    @Operation(summary = "One package with its sites and current price",
            description = "'at' evaluates the price as of that date (default today).")
    @GetMapping("/packages/{id}")
    public PackageResponse get(@PathVariable int id,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate at) {
        return packageService.get(id, at);
    }

    @Operation(summary = "Add a package",
            description = "Required: name (unique). Optional, to set in the same call: attractionIds (the included sites). "
                    + "Set the price afterwards with PUT /rates/package.")
    @PostMapping("/packages")
    public ResponseEntity<PackageResponse> create(@RequestBody Map<String, Object> body) {
        PackageResponse created = packageService.create(body);
        return ResponseEntity.created(URI.create("/api/v1/master-data/packages/" + created.id())).body(created);
    }

    @Operation(summary = "Quick edit: rename a package",
            description = "Send name. The included sites have their own call and the price is a rate.")
    @PatchMapping("/packages/{id}")
    public PackageResponse update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return packageService.update(id, body);
    }

    @Operation(summary = "Delete a package",
            description = "Removes the package and its site links. Refused with 409 once the package has any price history.")
    @DeleteMapping("/packages/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        packageService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Replace the attractions included in the package",
            description = "Send the complete list of attraction ids (use [] to clear it). Returned by name.")
    @PutMapping("/packages/{id}/sites")
    public List<RefResponse> replaceSites(@PathVariable int id, @RequestBody List<Integer> attractionIds) {
        return packageService.replaceSites(id, attractionIds);
    }
}
