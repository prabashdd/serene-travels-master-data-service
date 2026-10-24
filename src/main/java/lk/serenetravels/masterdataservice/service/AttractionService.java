package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.dto.AttractionResponse;
import lk.serenetravels.masterdataservice.dto.AttractionSummaryResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.TukTukResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// An attraction (a priced site, activity or charge) with its current prices and tuk-tuk option.
// The prices themselves are edited through the rate engine (family "attraction", and "tuk-tuk" for fares).
public interface AttractionService {

    // `sort` entries are "field,direction" (fields: name, destination, pricingMethod, id).
    PageResponse<AttractionSummaryResponse> list(String q, Integer destinationId, Boolean safari, String pricingMethod,
                                                 List<String> sort, int page, int size);

    // Includes every rate in force on `at` (default today).
    AttractionResponse get(int id, LocalDate at);

    AttractionResponse create(Map<String, Object> body);

    // Partial update: only the fields present in the body change; the result must still be consistent.
    AttractionResponse update(int id, Map<String, Object> body);

    // Hard delete (attractions have no active flag); 409 IN_USE while rates, a tuk-tuk option or a package use it.
    void delete(int id);

    // Gives the attraction a tuk-tuk option (idempotent) so tuk-tuk fares can be set for it.
    TukTukResponse enableTukTuk(int attractionId);

    // Removes the option; 409 IN_USE while fares exist for it.
    void disableTukTuk(int attractionId);
}
