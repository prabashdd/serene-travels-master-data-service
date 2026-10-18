package lk.serenetravels.masterdataservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lk.serenetravels.masterdataservice.service.LookupService;
import org.springframework.http.CacheControl;
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
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Tag(name = "Lookups", description = "Cold reference data. Fetch the bundle once and cache it.")
@RestController
@RequestMapping("/api/v1/master-data/lookups")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class LookupController {

    private final LookupService lookupService;

    public LookupController(LookupService lookupService) {
        this.lookupService = lookupService;
    }

    @Operation(summary = "All reference lookups in one response (bootstrap for dropdowns)",
            description = "Returns an ETag and Cache-Control: no-cache, so the FE revalidates with "
                    + "If-None-Match and gets a cheap 304 until something actually changes.")
    @GetMapping
    public ResponseEntity<Map<String, List<Map<String, Object>>>> bundle(
            @Parameter(description = "Include soft-deleted items") @RequestParam(defaultValue = "false")
            boolean includeInactive,
            WebRequest request) {
        Map<String, List<Map<String, Object>>> bundle = lookupService.bundle(includeInactive);
        // Map/List hashCode is content-based, so identical data gives an identical ETag.
        String etag = "\"" + Integer.toHexString(bundle.hashCode()) + (includeInactive ? "-all" : "") + "\"";
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noCache()).body(bundle);
    }

    @Operation(summary = "One lookup list")
    @GetMapping("/{name}")
    public List<Map<String, Object>> list(@PathVariable String name,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return lookupService.list(name, includeInactive);
    }

    @Operation(summary = "Add a lookup item")
    @PostMapping("/{name}")
    public ResponseEntity<Map<String, Object>> create(@PathVariable String name,
            @RequestBody Map<String, Object> body) {
        Map<String, Object> created = lookupService.create(name, body);
        return ResponseEntity.created(URI.create("/api/v1/master-data/lookups/" + name + "/" + created.get("id")))
                .body(created);
    }

    @Operation(summary = "Edit a lookup item (send only the changed fields)")
    @PatchMapping("/{name}/{id}")
    public Map<String, Object> update(@PathVariable String name, @PathVariable int id,
            @RequestBody Map<String, Object> body) {
        return lookupService.update(name, id, body);
    }

    @Operation(summary = "Delete a lookup item",
            description = "409 IN_USE if other master data references it. technical-visit-categories is "
                    + "soft-deleted (active=false) instead.")
    @DeleteMapping("/{name}/{id}")
    public ResponseEntity<Void> delete(@PathVariable String name, @PathVariable int id) {
        lookupService.delete(name, id);
        return ResponseEntity.noContent().build();
    }
}
