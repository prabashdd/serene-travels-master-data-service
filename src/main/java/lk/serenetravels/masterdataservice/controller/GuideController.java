package lk.serenetravels.masterdataservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lk.serenetravels.masterdataservice.dto.GuideResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.service.GuideService;
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

@Tag(name = "Guides", description = "Guides and the languages each speaks. Guide accommodation costs are "
        + "Rates endpoints (family 'guide-accommodation').")
@RestController
@RequestMapping("/api/v1/master-data")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class GuideController {

    private final GuideService guideService;

    public GuideController(GuideService guideService) {
        this.guideService = guideService;
    }

    @Operation(summary = "List guides",
            description = "Each guide comes with the languages they speak. Filter with q (name, nickname, guide number, "
                    + "NIC, phone, email or town), guideType (DRIVER, CHAUFFEUR_GUIDE or NATIONAL_GUIDE), languageId and "
                    + "active. Sort with sort=name,asc (repeatable); fields: name, guideNo, guideType, town, id.")
    @GetMapping("/guides")
    public PageResponse<GuideResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String guideType,
            @RequestParam(required = false) Integer languageId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "field,direction - repeatable, e.g. sort=town,asc&sort=name,asc")
            HttpServletRequest request) {
        // Read the raw values: a bound List<String> would split "name,asc" on the comma.
        String[] sort = request.getParameterValues("sort");
        return guideService.list(q, guideType, languageId, active, sort == null ? List.of() : Arrays.asList(sort), page, size);
    }

    @Operation(summary = "One guide with their languages")
    @GetMapping("/guides/{id}")
    public GuideResponse get(@PathVariable int id) {
        return guideService.get(id);
    }

    @Operation(summary = "Add a guide",
            description = "Required: guideNo (unique, stored upper-case), name, guideType. Optional: nickname, nic "
                    + "(unique), contact, email, address, town, active (default true), and languageIds - a list of "
                    + "language ids to set the languages in the same call.")
    @PostMapping("/guides")
    public ResponseEntity<GuideResponse> create(@RequestBody Map<String, Object> body) {
        GuideResponse created = guideService.create(body);
        return ResponseEntity.created(URI.create("/api/v1/master-data/guides/" + created.id())).body(created);
    }

    @Operation(summary = "Quick edit: send only the fields that changed",
            description = "Send null to clear an optional field. Languages are changed with PUT /guides/{id}/languages.")
    @PatchMapping("/guides/{id}")
    public GuideResponse update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return guideService.update(id, body);
    }

    @Operation(summary = "Deactivate a guide (soft delete)",
            description = "Sets active=false; the guide stays in the database. Bring back with {\"active\": true}.")
    @DeleteMapping("/guides/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable int id) {
        guideService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Replace the languages the guide speaks",
            description = "Send the complete list of language ids (use [] to clear it). The choices come from the "
                    + "'languages' lookup.")
    @PutMapping("/guides/{id}/languages")
    public List<RefResponse> replaceLanguages(@PathVariable int id, @RequestBody List<Integer> languageIds) {
        return guideService.replaceLanguages(id, languageIds);
    }
}
