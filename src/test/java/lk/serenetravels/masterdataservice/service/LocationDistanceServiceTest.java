package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.TestDatabase;
import lk.serenetravels.masterdataservice.dto.LocationDistanceResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.exception.ApiException;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.impl.LocationDistanceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocationDistanceServiceTest {

    private JdbcClient jdbc;
    private LocationDistanceServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.fresh();
        service = new LocationDistanceServiceImpl(jdbc);
        // ids 1..4
        jdbc.sql("insert into destination (name, country) values ('Colombo', 'Sri Lanka'), ('Kandy', 'Sri Lanka'), "
                + "('Sigiriya', 'Sri Lanka'), ('Galle', 'Sri Lanka')").update();
    }

    // ------------------------------------------------------------------ create / read

    @Test
    void createReturnsTheDistanceWithBothEndsByName() {
        LocationDistanceResponse created = service.create(body("fromDestinationId", 1, "toDestinationId", 2,
                "routeType", "road", "distanceKm", 115.5, "durationMinutes", 210));

        assertThat(created.id()).isNotNull();
        assertThat(created.from().name()).isEqualTo("Colombo");
        assertThat(created.to().name()).isEqualTo("Kandy");
        assertThat(created.routeType()).isEqualTo("ROAD");
        assertThat(created.distanceKm()).isEqualByComparingTo("115.5");
        assertThat(created.durationMinutes()).isEqualTo(210);
        assertThat(created.needsReview()).isFalse();
    }

    @Test
    void theDurationIsOptionalAndWholeKilometresAreAccepted() {
        LocationDistanceResponse created = distance(1, 2, "ROAD", "140");

        assertThat(created.durationMinutes()).isNull();
        assertThat(created.distanceKm()).isEqualByComparingTo("140");
    }

    @Test
    void createReportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> service.create(body("colour", "red")))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getFieldErrors())
                        .containsKeys("colour", "fromDestinationId", "toDestinationId", "routeType", "distanceKm"));
        assertThatThrownBy(() -> service.create(body("fromDestinationId", 98, "toDestinationId", 99, "routeType", "no way",
                "distanceKm", -5, "durationMinutes", 0)))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getFieldErrors())
                        .containsKeys("fromDestinationId", "toDestinationId", "routeType", "distanceKm", "durationMinutes"));
        assertField(() -> service.create(body("fromDestinationId", 1, "toDestinationId", 1, "routeType", "ROAD", "distanceKm", 5)), "toDestinationId");
        assertField(() -> service.create(body("fromDestinationId", 1, "toDestinationId", 2, "routeType", "ROAD", "distanceKm", 0)), "distanceKm");
        assertField(() -> service.create(body("fromDestinationId", 1, "toDestinationId", 2, "routeType", "ROAD", "distanceKm", 5000.1)), "distanceKm");
        assertField(() -> service.create(body("fromDestinationId", 1, "toDestinationId", 2, "routeType", "ROAD", "distanceKm", 12.34)), "distanceKm");
        assertField(() -> service.create(body("fromDestinationId", 1, "toDestinationId", 2, "routeType", "ROAD", "distanceKm", "far")), "distanceKm");
        assertField(() -> service.create(body("fromDestinationId", 1, "toDestinationId", 2, "routeType", "ROAD", "distanceKm", 5, "durationMinutes", 3000)), "durationMinutes");
        assertThat(jdbc.sql("select count(*) from location_distance").query(Long.class).single()).isZero();   // nothing half-saved
    }

    @Test
    void theSameRouteCannotBeRecordedTwiceInEitherDirection() {
        int first = distance(1, 2, "ROAD", "115").id();

        assertThatThrownBy(() -> distance(1, 2, "road", "120"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        assertThatThrownBy(() -> distance(2, 1, "ROAD", "115"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getMessage()).contains("id " + first));
        // another route type, or another pair, is fine
        assertThat(distance(1, 2, "ROADA", "125").id()).isNotNull();
        assertThat(distance(1, 3, "ROAD", "170").id()).isNotNull();
    }

    @Test
    void getAnUnknownDistanceIs404() {
        assertThatThrownBy(() -> service.get(99)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ between

    @Test
    void betweenFindsEveryRouteInEitherDirectionShortestFirst() {
        distance(1, 2, "ROADB", "130");
        distance(2, 1, "ROADA", "118");          // stored the other way round
        distance(1, 3, "ROAD", "170");           // a different pair

        List<LocationDistanceResponse> forward = service.between(1, 2);
        assertThat(forward).extracting(LocationDistanceResponse::routeType).containsExactly("ROADA", "ROADB");
        assertThat(forward.get(0).from().name()).isEqualTo("Kandy");        // as stored
        assertThat(service.between(2, 1)).extracting(LocationDistanceResponse::id)
                .containsExactlyElementsOf(forward.stream().map(LocationDistanceResponse::id).toList());
        assertThat(service.between(1, 4)).isEmpty();
    }

    @Test
    void betweenRejectsUnknownDestinations() {
        assertThatThrownBy(() -> service.between(1, 99)).isInstanceOfSatisfying(InvalidRequestException.class,
                e -> assertThat(e.getFieldErrors()).containsKey("toDestinationId"));
        assertThatThrownBy(() -> service.between(99, 1)).isInstanceOfSatisfying(InvalidRequestException.class,
                e -> assertThat(e.getFieldErrors()).containsKey("fromDestinationId"));
    }

    // ------------------------------------------------------------------ update / delete

    @Test
    void updateChangesOnlyWhatWasSent() {
        int id = service.create(body("fromDestinationId", 1, "toDestinationId", 2, "routeType", "ROAD",
                "distanceKm", 115, "durationMinutes", 210)).id();

        LocationDistanceResponse longer = service.update(id, body("distanceKm", "118.5"));
        assertThat(longer.distanceKm()).isEqualByComparingTo("118.5");
        assertThat(longer.durationMinutes()).isEqualTo(210);
        assertThat(longer.routeType()).isEqualTo("ROAD");

        assertThat(service.update(id, body("durationMinutes", null)).durationMinutes()).isNull();
        LocationDistanceResponse moved = service.update(id, body("toDestinationId", 3, "routeType", "roada"));
        assertThat(moved.to().name()).isEqualTo("Sigiriya");
        assertThat(moved.routeType()).isEqualTo("ROADA");
    }

    @Test
    void updateRejectsEmptyBodiesBadValuesClashesAndUnknownIds() {
        int first = distance(1, 2, "ROAD", "115").id();
        distance(1, 3, "ROAD", "170");

        assertThatThrownBy(() -> service.update(first, Map.of())).isInstanceOf(InvalidRequestException.class);
        assertField(() -> service.update(first, body("toDestinationId", 1)), "toDestinationId");     // would point at itself
        assertField(() -> service.update(first, body("distanceKm", 0)), "distanceKm");
        assertField(() -> service.update(first, body("durationMinutes", 0)), "durationMinutes");
        assertField(() -> service.update(first, body("routeType", "no way")), "routeType");
        assertField(() -> service.update(first, body("fromDestinationId", 99)), "fromDestinationId");
        assertField(() -> service.update(first, body("colour", "red")), "colour");
        assertThatThrownBy(() -> service.update(first, body("toDestinationId", 3)))                  // 1 -> 3 ROAD exists
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        assertThatThrownBy(() -> service.update(99, body("distanceKm", 5))).isInstanceOf(NotFoundException.class);
        assertThat(service.get(first).to().name()).isEqualTo("Kandy");                               // refused changes left it alone
    }

    @Test
    void anExistingPairHeldBothWaysCanStillHaveItsDistanceEdited() {
        // The legacy data holds a few pairs in both directions; they must not become uneditable.
        int there = distance(1, 2, "ROAD", "115").id();
        jdbc.sql("insert into location_distance (from_destination_id, to_destination_id, route_type, distance_km) "
                + "values (2, 1, 'ROAD', 116)").update();

        assertThat(service.update(there, body("distanceKm", 116)).distanceKm()).isEqualByComparingTo("116");
    }

    @Test
    void suspectLegacyRowsAreFlaggedAndFixedByCorrectingTheWholeRow() {
        jdbc.sql("insert into location_distance (from_destination_id, to_destination_id, route_type, distance_km) "
                + "values (1, 1, 'ROAD', 5), (1, 2, 'ROAD', 0), (1, 3, 'ROAD', -100), (1, 4, 'ROAD', 60)").update();

        List<LocationDistanceResponse> suspect = service.list(null, null, null, null, null, true, List.of("to,asc"), 0, 20).content();
        assertThat(suspect).extracting(r -> r.to().name()).containsExactly("Colombo", "Kandy", "Sigiriya");
        assertThat(suspect).allSatisfy(r -> assertThat(r.needsReview()).isTrue());
        assertThat(service.list(null, null, null, null, null, false, null, 0, 20).content())
                .singleElement().satisfies(r -> assertThat(r.to().name()).isEqualTo("Galle"));

        int selfLoop = suspect.get(0).id();
        assertField(() -> service.update(selfLoop, body("durationMinutes", 30)), "toDestinationId");   // still wrong: refused
        LocationDistanceResponse fixed = service.update(selfLoop, body("toDestinationId", 3, "distanceKm", 200, "routeType", "ROADA"));
        assertThat(fixed.needsReview()).isFalse();
        assertThat(service.list(null, null, null, null, null, true, null, 0, 20).totalElements()).isEqualTo(2);
    }

    @Test
    void deleteRemovesTheDistance() {
        int id = distance(1, 2, "ROAD", "115").id();

        service.delete(id);

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ list

    @Test
    void listFiltersSearchesSortsAndPages() {
        distance(1, 2, "ROAD", "115");
        distance(1, 2, "ROADA", "125");
        distance(2, 3, "ROAD", "90");
        distance(4, 1, "ROADB", "120");

        assertThat(service.list(null, null, null, null, null, null, null, 0, 20).content())
                .extracting(r -> r.from().name() + ">" + r.to().name() + " " + r.routeType())
                .containsExactly("Colombo>Kandy ROAD", "Colombo>Kandy ROADA", "Galle>Colombo ROADB", "Kandy>Sigiriya ROAD");
        assertThat(service.list("sigi", null, null, null, null, null, null, 0, 20).totalElements()).isEqualTo(1);
        assertThat(service.list(null, 1, null, null, null, null, null, 0, 20).totalElements()).isEqualTo(2);        // from Colombo
        assertThat(service.list(null, null, 1, null, null, null, null, 0, 20).totalElements()).isEqualTo(1);        // to Colombo
        assertThat(service.list(null, null, null, 1, null, null, null, 0, 20).totalElements()).isEqualTo(3);        // either end
        assertThat(service.list(null, null, null, null, "roada", null, null, 0, 20).totalElements()).isEqualTo(1);
        assertThat(service.list(null, null, null, null, null, null, List.of("distanceKm,desc"), 0, 20).content().get(0).distanceKm())
                .isEqualByComparingTo("125");

        PageResponse<LocationDistanceResponse> second = service.list(null, null, null, null, null, null, null, 1, 3);
        assertThat(second.totalElements()).isEqualTo(4);
        assertThat(second.content()).hasSize(1);
        assertThat(second.last()).isTrue();
    }

    @Test
    void searchTreatsPercentAndUnderscoreAsPlainText() {
        distance(1, 2, "ROAD", "115");

        assertThat(service.list("%", null, null, null, null, null, null, 0, 20).content()).isEmpty();
        assertThat(service.list("_", null, null, null, null, null, null, 0, 20).content()).isEmpty();
    }

    @Test
    void listRejectsBadSortAndPaging() {
        assertField(() -> service.list(null, null, null, null, null, null, List.of("password,asc"), 0, 20), "sort");
        assertThatThrownBy(() -> service.list(null, null, null, null, null, null, null, 0, 0)).isInstanceOf(InvalidRequestException.class);
    }

    // ------------------------------------------------------------------ helpers

    private LocationDistanceResponse distance(int from, int to, String routeType, String km) {
        return service.create(body("fromDestinationId", from, "toDestinationId", to, "routeType", routeType, "distanceKm", km));
    }

    // Builds a JSON-style body from key/value pairs; unlike Map.of it allows null values (meaning "clear").
    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static void assertField(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getFieldErrors()).containsKey(field));
    }
}
