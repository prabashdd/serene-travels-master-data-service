package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.PromotionResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// Promotions: a named discount with a type, a validity window and optional limits (destination, season, minimum
// group size). The amount itself is versioned and set through /rates/promotion. Promotions are soft-deleted.
public interface PromotionService {

    // `validOn` keeps promotions whose window [validFrom, validTo) contains that date. `at` (default today) is the date
    // the status and current value are worked out for. Sort fields: name, type, validFrom, validTo, id.
    PageResponse<PromotionResponse> list(String q, String type, Boolean active, LocalDate validOn, Integer destinationId,
                                         Integer seasonId, LocalDate at, List<String> sort, int page, int size);

    PromotionResponse get(int id, LocalDate at);

    PromotionResponse create(Map<String, Object> body);

    // Partial update. The type cannot change once a value has been set (the value's meaning would change with it).
    PromotionResponse update(int id, Map<String, Object> body);

    // Sets active=false; the promotion and its value history stay.
    void deactivate(int id);
}
