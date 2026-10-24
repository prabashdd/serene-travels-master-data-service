package lk.serenetravels.masterdataservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lk.serenetravels.masterdataservice.dto.DriverResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.service.DriverService;
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

@Tag(name = "Drivers", description = "Drivers and their assistants.")
@RestController
@RequestMapping("/api/v1/master-data")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class DriverController {

    private final DriverService driverService;

    public DriverController(DriverService driverService) {
        this.driverService = driverService;
    }

    @Operation(summary = "List drivers and assistants",
            description = "Each person comes with the vehicles they are assigned to. Filter with q (name, NIC, phone or "
                    + "licence number), role (DRIVER or ASSISTANT), active and chauffeur. Sort with sort=name,asc "
                    + "(repeatable); fields: name, role, nic, id.")
    @GetMapping("/drivers")
    public PageResponse<DriverResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean chauffeur,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "field,direction - repeatable, e.g. sort=name,asc")
            HttpServletRequest request) {
        // Read the raw values: a bound List<String> would split "name,asc" on the comma.
        String[] sort = request.getParameterValues("sort");
        return driverService.list(q, role, active, chauffeur, sort == null ? List.of() : Arrays.asList(sort), page, size);
    }

    @Operation(summary = "One driver or assistant")
    @GetMapping("/drivers/{id}")
    public DriverResponse get(@PathVariable int id) {
        return driverService.get(id);
    }

    @Operation(summary = "Add a driver or assistant",
            description = "Required: name, nic (unique). Optional: title, nameWithInitials, shortname, gender "
                    + "(MALE, FEMALE, OTHER), dateOfBirth, address, contact, mobile2, licenseNo, role (DRIVER by "
                    + "default, or ASSISTANT), isChauffeur (default false; DRIVER only), active (default true).")
    @PostMapping("/drivers")
    public ResponseEntity<DriverResponse> create(@RequestBody Map<String, Object> body) {
        DriverResponse created = driverService.create(body);
        return ResponseEntity.created(URI.create("/api/v1/master-data/drivers/" + created.id())).body(created);
    }

    @Operation(summary = "Quick edit: send only the fields that changed",
            description = "Send null to clear an optional field. A person cannot change role while assigned to a "
                    + "vehicle in the other slot (409 IN_USE).")
    @PatchMapping("/drivers/{id}")
    public DriverResponse update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return driverService.update(id, body);
    }

    @Operation(summary = "Deactivate a driver (soft delete)",
            description = "Sets active=false. Vehicles they were assigned to show a DRIVER_INACTIVE warning until reassigned.")
    @DeleteMapping("/drivers/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable int id) {
        driverService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
