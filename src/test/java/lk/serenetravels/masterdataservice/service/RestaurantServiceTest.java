package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.TestDatabase;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.dto.RestaurantResponse;
import lk.serenetravels.masterdataservice.exception.ApiException;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.impl.RestaurantServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestaurantServiceTest {

    private JdbcClient jdbc;
    private RestaurantServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.fresh();
        service = new RestaurantServiceImpl(jdbc);
        jdbc.sql("insert into destination (name, country) values ('Sigiriya', 'Sri Lanka'), ('Kandy', 'Sri Lanka')").update();
        jdbc.sql("insert into dietary_category (name) values ('Vegetarian'), ('Non-Vegetarian'), ('Jain'), ('Halal'), ('Chinese')").update();
    }

    // ------------------------------------------------------------------ create / read

    @Test
    void createReturnsTheRestaurantWithDaysInWeekOrderAndDietary() {
        RestaurantResponse restaurant = service.create(body("name", "Kamatha", "destinationId", 1,
                "closedDays", List.of("sunday", "Monday"), "dietaryCategoryIds", List.of(4, 1)));

        assertThat(restaurant.id()).isNotNull();
        assertThat(restaurant.destination().name()).isEqualTo("Sigiriya");
        assertThat(restaurant.closedDays()).containsExactly("MONDAY", "SUNDAY");          // Monday first
        assertThat(restaurant.dietary()).extracting(RefResponse::name).containsExactly("Vegetarian", "Halal");
    }

    @Test
    void daysAreStoredWithZeroAsSunday() {
        int id = restaurant("Kamatha", 1, "SUNDAY", "MONDAY", "WEDNESDAY", "SATURDAY").id();

        // The database documents day_of_week as 0 = Sunday .. 6 = Saturday; the API hides that from callers.
        assertThat(jdbc.sql("select day_of_week from restaurant_closed_day where restaurant_id = :id order by day_of_week")
                .param("id", id).query(Integer.class).list()).containsExactly(0, 1, 3, 6);
    }

    @Test
    void aRestaurantCanBeCreatedWithNoDaysOrDietary() {
        RestaurantResponse restaurant = service.create(body("name", "Open Daily", "destinationId", 1));

        assertThat(restaurant.closedDays()).isEmpty();
        assertThat(restaurant.dietary()).isEmpty();
    }

    @Test
    void createReportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> service.create(body("colour", "red")))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        e -> assertThat(e.getFieldErrors()).containsKeys("colour", "name", "destinationId"));
        assertThatThrownBy(() -> service.create(body("name", "X", "destinationId", 99, "closedDays", List.of("Funday"),
                "dietaryCategoryIds", List.of(99))))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getFieldErrors())
                        .containsKeys("destinationId", "closedDays[0]", "dietaryCategoryIds[0]"));
        assertField(() -> service.create(body("name", "X", "destinationId", 1, "closedDays", "Monday")), "closedDays");
        assertField(() -> service.create(body("name", "X", "destinationId", 1, "closedDays", List.of("Monday", "MONDAY"))), "closedDays[1]");
        assertField(() -> service.create(body("name", "X", "destinationId", 1, "closedDays", Arrays.asList("Monday", null))), "closedDays[1]");
        assertField(() -> service.create(body("name", "X", "destinationId", 1, "dietaryCategoryIds", List.of(1, 1))), "dietaryCategoryIds[1]");
        assertThat(jdbc.sql("select count(*) from restaurant").query(Long.class).single()).isZero();     // nothing half-saved
    }

    @Test
    void aNameIsUniqueWithinADestinationOnly() {
        restaurant("Kamatha", 1);

        assertThatThrownBy(() -> restaurant("kamatha", 1))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        assertThat(restaurant("Kamatha", 2).id()).isNotNull();
    }

    @Test
    void getAnUnknownRestaurantIs404() {
        assertThatThrownBy(() -> service.get(99)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ update / delete

    @Test
    void updateRenamesOrMovesAndLeavesTheRestAlone() {
        int id = restaurant("Kamatha", 1, "MONDAY").id();

        RestaurantResponse renamed = service.update(id, body("name", "Kamatha Garden"));
        assertThat(renamed.name()).isEqualTo("Kamatha Garden");
        assertThat(renamed.closedDays()).containsExactly("MONDAY");

        RestaurantResponse moved = service.update(id, body("destinationId", 2));
        assertThat(moved.destination().name()).isEqualTo("Kandy");
        assertThat(moved.closedDays()).containsExactly("MONDAY");
    }

    @Test
    void updateRejectsEmptyBodiesUnknownFieldsClashesAndUnknownIds() {
        int first = restaurant("Kamatha", 1).id();
        restaurant("Elephant Corner", 1);

        assertThatThrownBy(() -> service.update(first, Map.of())).isInstanceOf(InvalidRequestException.class);
        assertField(() -> service.update(first, body("closedDays", List.of("MONDAY"))), "closedDays");   // has its own call
        assertField(() -> service.update(first, body("destinationId", 99)), "destinationId");
        assertThatThrownBy(() -> service.update(first, body("name", "elephant corner")))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        assertThat(service.update(first, body("name", "kamatha")).name()).isEqualTo("kamatha");     // own name is fine
        // Moving into a destination that already has that name is a clash too.
        restaurant("Kamatha", 2);
        assertThatThrownBy(() -> service.update(first, body("name", "Kamatha", "destinationId", 2)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.update(99, body("name", "x"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteRemovesTheRestaurantAndItsLinks() {
        int id = restaurant("Kamatha", 1, "MONDAY").id();
        service.replaceDietary(id, List.of(1, 2));

        service.delete(id);

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(NotFoundException.class);
        assertThat(jdbc.sql("select count(*) from restaurant_closed_day").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from restaurant_dietary_capability").query(Long.class).single()).isZero();
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ closed days and dietary

    @Test
    void closedDaysAreReplacedAsAWholeSetAndReturnedInWeekOrder() {
        int id = restaurant("Kamatha", 1).id();

        assertThat(service.replaceClosedDays(id, List.of("Sunday", "tuesday", "FRIDAY"))).containsExactly("TUESDAY", "FRIDAY", "SUNDAY");
        assertThat(service.replaceClosedDays(id, List.of("monday"))).containsExactly("MONDAY");
        assertThat(service.get(id).closedDays()).containsExactly("MONDAY");
        assertThat(service.replaceClosedDays(id, List.of())).isEmpty();
        assertThat(service.get(id).closedDays()).isEmpty();
    }

    @Test
    void aRefusedClosedDaysChangeLeavesTheOldOnesInPlace() {
        int id = restaurant("Kamatha", 1, "MONDAY", "TUESDAY").id();

        assertField(() -> service.replaceClosedDays(id, List.of("Monday", "Someday")), "days[1]");
        assertField(() -> service.replaceClosedDays(id, List.of("Monday", "monday")), "days[1]");
        assertThatThrownBy(() -> service.replaceClosedDays(id, null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.replaceClosedDays(99, List.of("Monday"))).isInstanceOf(NotFoundException.class);
        assertThat(service.get(id).closedDays()).containsExactly("MONDAY", "TUESDAY");
    }

    @Test
    void dietarySupportIsReplacedAndValidated() {
        int id = restaurant("Kamatha", 1).id();

        assertThat(service.replaceDietary(id, List.of(5, 1))).extracting(RefResponse::name).containsExactly("Vegetarian", "Chinese");
        assertThat(service.replaceDietary(id, List.of(3))).extracting(RefResponse::name).containsExactly("Jain");
        assertThat(service.replaceDietary(id, List.of())).isEmpty();

        service.replaceDietary(id, List.of(1));
        assertField(() -> service.replaceDietary(id, Arrays.asList(1, 99)), "dietaryCategoryIds[1]");
        assertField(() -> service.replaceDietary(id, List.of(2, 2)), "dietaryCategoryIds[1]");
        assertThatThrownBy(() -> service.replaceDietary(id, null)).isInstanceOf(InvalidRequestException.class);
        assertThat(service.get(id).dietary()).extracting(RefResponse::name).containsExactly("Vegetarian");
    }

    // ------------------------------------------------------------------ list

    @Test
    void listFiltersSearchesSortsAndPages() {
        int kamatha = restaurant("Kamatha", 1, "MONDAY").id();
        int corner = restaurant("Elephant Corner", 1).id();
        int kandy = restaurant("Kandy Lake View", 2, "MONDAY", "SUNDAY").id();
        restaurant("Spice Garden", 2, "FRIDAY");
        service.replaceDietary(kamatha, List.of(1));
        service.replaceDietary(corner, List.of(1, 4));
        service.replaceDietary(kandy, List.of(4));

        assertThat(names(service.list(null, null, null, null, null, null, 0, 20)))
                .containsExactly("Elephant Corner", "Kamatha", "Kandy Lake View", "Spice Garden");
        assertThat(names(service.list("KAND", null, null, null, null, null, 0, 20))).containsExactly("Kandy Lake View");
        assertThat(names(service.list(null, 1, null, null, null, null, 0, 20))).containsExactly("Elephant Corner", "Kamatha");
        assertThat(names(service.list(null, null, 4, null, null, null, 0, 20))).containsExactly("Elephant Corner", "Kandy Lake View"); // halal
        assertThat(names(service.list(null, null, null, "monday", null, null, 0, 20))).containsExactly("Kamatha", "Kandy Lake View");
        assertThat(names(service.list(null, null, null, null, "MONDAY", null, 0, 20))).containsExactly("Elephant Corner", "Spice Garden");
        assertThat(names(service.list(null, null, null, "Sunday", null, null, 0, 20))).containsExactly("Kandy Lake View");
        assertThat(names(service.list(null, null, null, null, null, List.of("destination,desc", "name,asc"), 0, 20)).get(0)).isEqualTo("Elephant Corner");   // Sigiriya sorts after Kandy

        PageResponse<RestaurantResponse> second = service.list(null, null, null, null, null, null, 1, 3);
        assertThat(second.totalElements()).isEqualTo(4);
        assertThat(second.content()).hasSize(1);
        assertThat(second.last()).isTrue();
        // every row of a page carries its days and dietary support
        RestaurantResponse first = service.list(null, null, null, null, null, List.of("name,asc"), 0, 20).content().get(0);
        assertThat(first.name()).isEqualTo("Elephant Corner");
        assertThat(first.dietary()).extracting(RefResponse::name).containsExactly("Vegetarian", "Halal");
    }

    @Test
    void searchTreatsPercentAndUnderscoreAsPlainText() {
        restaurant("Kamatha", 1);

        assertThat(service.list("%", null, null, null, null, null, 0, 20).content()).isEmpty();
        assertThat(service.list("_", null, null, null, null, null, 0, 20).content()).isEmpty();
    }

    @Test
    void listRejectsBadDaysSortAndPaging() {
        assertField(() -> service.list(null, null, null, "Someday", null, null, 0, 20), "closedOn");
        assertField(() -> service.list(null, null, null, null, "Someday", null, 0, 20), "openOn");
        assertField(() -> service.list(null, null, null, null, null, List.of("password,asc"), 0, 20), "sort");
        assertThatThrownBy(() -> service.list(null, null, null, null, null, null, 0, 0)).isInstanceOf(InvalidRequestException.class);
    }

    // ------------------------------------------------------------------ helpers

    private RestaurantResponse restaurant(String name, int destinationId, String... closedDays) {
        return service.create(body("name", name, "destinationId", destinationId, "closedDays", List.of(closedDays)));
    }

    private static List<String> names(PageResponse<RestaurantResponse> page) {
        return page.content().stream().map(RestaurantResponse::name).toList();
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
