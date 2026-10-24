package lk.serenetravels.masterdataservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.VehicleResponse;
import lk.serenetravels.masterdataservice.service.VehicleService;
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

@Tag(name = "Vehicles", description = "The fleet. A vehicle's per-km price comes from its vehicle TYPE "
        + "(Rates endpoints, family 'vehicle'), not from the vehicle.")
@RestController
@RequestMapping("/api/v1/master-data")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @Operation(summary = "List vehicles",
            description = "Each vehicle comes with its type, driver, assistant and a list of warnings (expired or "
                    + "expiring insurance / revenue licence, no driver, inactive driver). Filter with q (registration or "
                    + "owner), vehicleTypeId, method (INTERNAL or CONTRACTED), active and expiringWithinDays "
                    + "(insurance or licence expires within N days - already-expired ones are included). Sort with "
                    + "sort=registrationNo,asc (repeatable); fields: registrationNo, type, seatingCapacity, "
                    + "insuranceExpiry, licenseExpiry, id.")
    @GetMapping("/vehicles")
    public PageResponse<VehicleResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer vehicleTypeId,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Integer expiringWithinDays,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "field,direction - repeatable, e.g. sort=insuranceExpiry,asc")
            HttpServletRequest request) {
        // Read the raw values: a bound List<String> would split "name,asc" on the comma.
        String[] sort = request.getParameterValues("sort");
        return vehicleService.list(q, vehicleTypeId, method, active, expiringWithinDays,
                sort == null ? List.of() : Arrays.asList(sort), page, size);
    }

    @Operation(summary = "One vehicle")
    @GetMapping("/vehicles/{id}")
    public VehicleResponse get(@PathVariable int id) {
        return vehicleService.get(id);
    }

    @Operation(summary = "Add a vehicle",
            description = "Required: registrationNo (unique; stored in upper case), vehicleTypeId, seatingCapacity "
                    + "(at most what the type carries), method (INTERNAL or CONTRACTED). Optional: ownerName, "
                    + "insuranceCompany, policyNumber, insuranceExpiryDate, revenueLicenseExpiryDate, driverId "
                    + "(an active DRIVER), assistantId (an active ASSISTANT), active (default true).")
    @PostMapping("/vehicles")
    public ResponseEntity<VehicleResponse> create(@RequestBody Map<String, Object> body) {
        VehicleResponse created = vehicleService.create(body);
        return ResponseEntity.created(URI.create("/api/v1/master-data/vehicles/" + created.id())).body(created);
    }

    @Operation(summary = "Quick edit: send only the fields that changed",
            description = "Typical edits: renew an expiry date, change the driver, bring a vehicle back with "
                    + "{\"active\": true}. Send null to clear an optional field such as assistantId.")
    @PatchMapping("/vehicles/{id}")
    public VehicleResponse update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return vehicleService.update(id, body);
    }

    @Operation(summary = "Take a vehicle out of service (soft delete)",
            description = "Sets active=false; the vehicle stays in the database.")
    @DeleteMapping("/vehicles/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable int id) {
        vehicleService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
