package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.dto.DriverResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;

import java.util.List;
import java.util.Map;

// Drivers and their assistants. A person is either a DRIVER or an ASSISTANT (the role decides which vehicle slot
// they can fill).
public interface DriverService {

    // `sort` entries are "field,direction" (fields: name, role, nic, id).
    PageResponse<DriverResponse> list(String q, String role, Boolean active, Boolean chauffeur, List<String> sort,
                                      int page, int size);

    DriverResponse get(int id);

    DriverResponse create(Map<String, Object> body);

    // Partial update: only the fields present in the body change.
    DriverResponse update(int id, Map<String, Object> body);

    // Soft delete (active = false). Reactivate with update({"active": true}).
    void deactivate(int id);
}
