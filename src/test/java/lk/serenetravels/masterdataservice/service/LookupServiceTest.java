package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.TestDatabase;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.impl.LookupServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LookupServiceTest {

    private JdbcClient jdbc;
    private LookupServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.fresh();
        service = new LookupServiceImpl(jdbc);
    }

    @Test
    void createReturnsTheStoredRowWithItsGeneratedId() {
        Map<String, Object> created = service.create("destinations",
                Map.of("name", "Habarana", "region", "North Central", "country", "Sri Lanka"));

        assertThat(created).containsEntry("name", "Habarana").containsEntry("region", "North Central");
        assertThat(created.get("id")).isInstanceOf(Integer.class);
        assertThat(service.list("destinations", false)).hasSize(1);
    }

    @Test
    void optionalFieldsMayBeOmitted() {
        Map<String, Object> created = service.create("destinations", Map.of("name", "Galle", "country", "Sri Lanka"));

        assertThat(created).containsEntry("region", null);
    }

    @Test
    void createReportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> service.create("nationalities", Map.of("colour", "red")))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getFieldErrors())
                        .containsKeys("colour", "name", "isoCode"));
        assertThatThrownBy(() -> service.create("destinations", Map.of("name", "  ", "country", "Sri Lanka")))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        e -> assertThat(e.getFieldErrors()).containsKey("name"));
    }

    @Test
    void typedColumnsAreParsedAndChecked() {
        Map<String, Object> season = service.create("seasons",
                Map.of("name", "Peak", "startDate", "2026-12-15", "endDate", "2027-01-15"));
        assertThat(season).containsEntry("startDate", LocalDate.of(2026, 12, 15));

        assertThatThrownBy(() -> service.create("seasons",
                Map.of("name", "Bad", "startDate", "15/12/2026", "endDate", "2027-01-15")))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        e -> assertThat(e.getFieldErrors()).containsKey("startDate"));
        assertThatThrownBy(() -> service.create("vehicle-types",
                Map.of("name", "Tuk", "minCapacity", "two", "maxCapacity", 3)))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        e -> assertThat(e.getFieldErrors()).containsKey("minCapacity"));
    }

    @Test
    void updateChangesOnlyTheGivenFields() {
        int id = (int) service.create("destinations",
                Map.of("name", "Kandy", "region", "Central", "country", "Sri Lanka")).get("id");

        Map<String, Object> updated = service.update("destinations", id, Map.of("region", "Hill Country"));

        assertThat(updated).containsEntry("name", "Kandy").containsEntry("region", "Hill Country");
    }

    @Test
    void updateCanClearAnOptionalFieldButNeverBlankARequiredOne() {
        int id = (int) service.create("destinations",
                Map.of("name", "Kandy", "region", "Central", "country", "Sri Lanka")).get("id");

        java.util.HashMap<String, Object> clear = new java.util.HashMap<>();
        clear.put("region", null);
        assertThat(service.update("destinations", id, clear)).containsEntry("region", null);

        java.util.HashMap<String, Object> blank = new java.util.HashMap<>();
        blank.put("name", "");
        assertThatThrownBy(() -> service.update("destinations", id, blank))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        e -> assertThat(e.getFieldErrors()).containsKey("name"));
    }

    @Test
    void updateRejectsAnEmptyBodyAndAnUnknownId() {
        int id = (int) service.create("languages", Map.of("name", "Sinhala")).get("id");

        assertThatThrownBy(() -> service.update("languages", id, Map.of())).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.update("languages", 999, Map.of("name", "Tamil")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteRemovesTheRow() {
        int id = (int) service.create("languages", Map.of("name", "Sinhala")).get("id");

        service.delete("languages", id);

        assertThat(service.list("languages", false)).isEmpty();
        assertThatThrownBy(() -> service.delete("languages", id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteIsRefusedWhileOtherDataReferencesTheRow() {
        int id = (int) service.create("destinations", Map.of("name", "Galle", "country", "Sri Lanka")).get("id");
        jdbc.sql("insert into property_dest_ref (destination_id) values (:id)").param("id", id).update();

        assertThatThrownBy(() -> service.delete("destinations", id)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getCode()).isEqualTo("IN_USE"));
        assertThat(service.list("destinations", false)).hasSize(1);
    }

    @Test
    void technicalVisitCategoriesAreSoftDeleted() {
        Map<String, Object> created = service.create("technical-visit-categories", Map.of("name", "Tea factory"));
        assertThat(created).containsEntry("active", true);
        int id = (int) created.get("id");

        service.delete("technical-visit-categories", id);

        assertThat(service.list("technical-visit-categories", false)).isEmpty();
        assertThat(service.list("technical-visit-categories", true)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("active", false));

        // And it can be brought back by editing the flag.
        assertThat(service.update("technical-visit-categories", id, Map.of("active", true)))
                .containsEntry("active", true);
    }

    @Test
    void bundleHoldsEveryLookupUnderItsDocumentedKey() {
        service.create("vehicle-types", Map.of("name", "Car", "minCapacity", 1, "maxCapacity", 2));

        Map<String, List<Map<String, Object>>> bundle = service.bundle(false);

        assertThat(bundle).containsOnlyKeys("destinations", "nationalities", "languages", "seasons", "vehicleTypes",
                "dietaryCategories", "mealPlans", "hotelInclusions", "miscCostTypes", "taxRates",
                "technicalVisitCategories");
        assertThat(bundle.get("vehicleTypes")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("minCapacity", 1).containsEntry("maxCapacity", 2));
    }

    @Test
    void unknownLookupNamesAreNotFound() {
        assertThatThrownBy(() -> service.list("spaceships", false)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.create("spaceships", Map.of())).isInstanceOf(NotFoundException.class);
    }
}
