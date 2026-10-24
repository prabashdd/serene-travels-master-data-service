package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.VehicleResponse;

import java.util.List;
import java.util.Map;

// The company's fleet. A vehicle's per-km price comes from its vehicle TYPE (rate family "vehicle"), not from
// the vehicle itself.
public interface VehicleService {

    // `sort` entries are "field,direction" (fields: registrationNo, type, seatingCapacity, insuranceExpiry,
    // licenseExpiry, id). expiringWithinDays keeps vehicles whose insurance or revenue licence expires on or
    // before that many days from today - already-expired ones included.
    PageResponse<VehicleResponse> list(String q, Integer vehicleTypeId, String method, Boolean active,
                                       Integer expiringWithinDays, List<String> sort, int page, int size);

    VehicleResponse get(int id);

    VehicleResponse create(Map<String, Object> body);

    // Partial update: only the fields present in the body change; send null to clear an optional field.
    VehicleResponse update(int id, Map<String, Object> body);

    // Soft delete (active = false). Reactivate with update({"active": true}).
    void deactivate(int id);
}
