package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RateAdjustItem;
import lk.serenetravels.masterdataservice.dto.RateAdjustRequest;
import lk.serenetravels.masterdataservice.dto.RateCorrectionRequest;
import lk.serenetravels.masterdataservice.dto.RateFamilyResponse;
import lk.serenetravels.masterdataservice.dto.RateRowResponse;
import lk.serenetravels.masterdataservice.dto.RateWriteRequest;
import lk.serenetravels.masterdataservice.dto.ResolvedRateResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface RateService {

    List<RateFamilyResponse> families();

    // `filter` maps key name -> value as text; `history` includes closed (past) rows.
    PageResponse<RateRowResponse> list(String family, Map<String, String> filter, LocalDate at, boolean history,
                                       int page, int size);

    // Closes the open row for these keys (if any) and opens a new one. For the vehicle family an
    // omitted seasonId applies to every season in one transaction.
    List<RateRowResponse> setRate(String family, RateWriteRequest request, String createdBy);

    RateRowResponse correct(String family, int id, RateCorrectionRequest request);

    // Only a not-yet-started (future-dated) row can be removed; history is never deleted.
    void deletePending(String family, int id);

    List<RateAdjustItem> adjust(String family, RateAdjustRequest request, String createdBy);

    // The single applicable price for a fully specified key, falling back to the "applies to all" row.
    ResolvedRateResponse resolve(String family, Map<String, String> keys, LocalDate at);

    // Saves a property's whole room type x season x nationality x occupancy grid atomically.
    List<RateRowResponse> saveGrid(int propertyId, List<RateWriteRequest> items, String createdBy);
}
