package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.dto.PackageResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RefResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// Site tour packages: a named bundle of attractions with one price. A site on a package stays bookable on its
// own through its attraction rates (FR-7.9). The price itself is versioned and set through /rates/package.
public interface PackageService {

    // `sort` entries are "field,direction" (fields: name, id). attractionId keeps packages that include that site.
    // `at` (default today) is the date the shown prices are in force.
    PageResponse<PackageResponse> list(String q, Integer attractionId, LocalDate at, List<String> sort, int page, int size);

    PackageResponse get(int id, LocalDate at);

    // The body may carry attractionIds to set the included sites in the same call.
    PackageResponse create(Map<String, Object> body);

    // Partial update of name only. Sites have their own call, the price is a rate.
    PackageResponse update(int id, Map<String, Object> body);

    // Removes the package and its site links. Refused (409) once the package has any price history.
    void delete(int id);

    // REPLACES the whole set of included attractions; returns them by name.
    List<RefResponse> replaceSites(int packageId, List<Integer> attractionIds);
}
