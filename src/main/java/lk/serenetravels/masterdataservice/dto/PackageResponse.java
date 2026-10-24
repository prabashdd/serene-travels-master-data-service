package lk.serenetravels.masterdataservice.dto;

import java.util.List;

// sites are the attractions the package includes (by name); currentRate is the package price in force on the
// requested date, or null when none is set (prices are changed through /rates/package).
public record PackageResponse(Integer id, String name, List<RefResponse> sites, RateAmount currentRate) {
}
