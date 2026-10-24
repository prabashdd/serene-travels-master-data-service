package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.dto.LocationDistanceResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;

import java.util.List;
import java.util.Map;

// Road distance and driving time between two destinations. There can be several route options for the same two
// destinations (route types such as ROAD, ROADA, ROADB), and most pairs are stored in one direction only, so
// "how far is A from B" must look both ways - see between().
public interface LocationDistanceService {

    // `destinationId` matches either end; `from`/`to` match one end exactly. `needsReview` = true keeps only suspect
    // rows (a destination to itself, or a distance not above zero). `q` searches both destination names.
    // Sort fields: from, to, routeType, distanceKm, durationMinutes, id.
    PageResponse<LocationDistanceResponse> list(String q, Integer fromDestinationId, Integer toDestinationId,
                                                Integer destinationId, String routeType, Boolean needsReview,
                                                List<String> sort, int page, int size);

    LocationDistanceResponse get(int id);

    // Every route option between the two destinations, stored in either direction, shortest first.
    List<LocationDistanceResponse> between(int destinationA, int destinationB);

    LocationDistanceResponse create(Map<String, Object> body);

    // Partial update of any field.
    LocationDistanceResponse update(int id, Map<String, Object> body);

    // Hard delete: nothing refers to a distance row.
    void delete(int id);
}
