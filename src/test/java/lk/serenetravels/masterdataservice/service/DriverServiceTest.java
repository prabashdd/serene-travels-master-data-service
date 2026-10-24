package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.TestDatabase;
import lk.serenetravels.masterdataservice.dto.DriverResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.exception.ApiException;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.impl.DriverServiceImpl;
import lk.serenetravels.masterdataservice.service.impl.VehicleServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DriverServiceTest {

    private JdbcClient jdbc;
    private DriverServiceImpl drivers;
    private VehicleServiceImpl vehicles;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneId.of("Asia/Colombo"));
        drivers = new DriverServiceImpl(jdbc, clock);
        vehicles = new VehicleServiceImpl(jdbc, clock);
        jdbc.sql("insert into vehicle_type (name, min_capacity, max_capacity) values ('Car', 1, 2), ('Van', 3, 9)").update();
    }

    // ------------------------------------------------------------------ create / read

    @Test
    void createReturnsThePersonWithDefaults() {
        DriverResponse created = drivers.create(body("name", "Nuwan Chameera", "nic", "831202289V"));

        assertThat(created.id()).isNotNull();
        assertThat(created.role()).isEqualTo("DRIVER");
        assertThat(created.isChauffeur()).isFalse();
        assertThat(created.active()).isTrue();
        assertThat(created.vehicles()).isEmpty();
    }

    @Test
    void codesTypedInAnyCaseAreStoredInCanonicalForm() {
        DriverResponse created = drivers.create(body("name", "Sunil", "nic", "1", "gender", "male", "role", "assistant"));

        assertThat(created.gender()).isEqualTo("MALE");
        assertThat(created.role()).isEqualTo("ASSISTANT");
    }

    @Test
    void createReportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> drivers.create(body("colour", "red")))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        e -> assertThat(e.getFieldErrors()).containsKeys("colour", "name", "nic"));
        assertField(() -> drivers.create(body("name", "A", "nic", "1", "role", "BOSS")), "role");
        assertField(() -> drivers.create(body("name", "A", "nic", "1", "gender", "robot")), "gender");
        assertField(() -> drivers.create(body("name", "A", "nic", "1", "dateOfBirth", "2099-01-01")), "dateOfBirth");
        assertField(() -> drivers.create(body("name", "A", "nic", "1", "dateOfBirth", "01/02/1980")), "dateOfBirth");
        assertField(() -> drivers.create(body("name", "A", "nic", "1", "role", "ASSISTANT", "isChauffeur", true)), "isChauffeur");
    }

    @Test
    void aNicBelongsToOnePersonWhateverTheCaseOrSpacing() {
        drivers.create(body("name", "A", "nic", "831202289 V"));

        assertThatThrownBy(() -> drivers.create(body("name", "B", "nic", " 831202289 v ")))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
    }

    @Test
    void getAnUnknownPersonIs404() {
        assertThatThrownBy(() -> drivers.get(99)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ update / deactivate

    @Test
    void updateChangesOnlyTheGivenFieldsAndCanClearOptionalOnes() {
        int id = drivers.create(body("name", "A", "nic", "1", "contact", "0771", "licenseNo", "B1")).id();

        DriverResponse renamed = drivers.update(id, body("name", "Anura"));
        assertThat(renamed.name()).isEqualTo("Anura");
        assertThat(renamed.contact()).isEqualTo("0771");

        assertThat(drivers.update(id, body("contact", null)).contact()).isNull();
        assertThat(drivers.update(id, body("isChauffeur", true)).isChauffeur()).isTrue();
    }

    @Test
    void updateRejectsEmptyBodiesNullFlagsAndTheOtherPersonsNic() {
        int first = drivers.create(body("name", "A", "nic", "1")).id();
        drivers.create(body("name", "B", "nic", "2"));

        assertThatThrownBy(() -> drivers.update(first, Map.of())).isInstanceOf(InvalidRequestException.class);
        assertField(() -> drivers.update(first, body("active", null)), "active");
        assertThatThrownBy(() -> drivers.update(first, body("nic", "2")))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        assertThat(drivers.update(first, body("nic", "1")).nic()).isEqualTo("1");   // own NIC is not a clash
        assertThatThrownBy(() -> drivers.update(99, body("name", "x"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void anAssistantCannotBeMarkedAChauffeurByAnUpdate() {
        int id = drivers.create(body("name", "A", "nic", "1", "role", "ASSISTANT")).id();

        assertField(() -> drivers.update(id, body("isChauffeur", true)), "isChauffeur");
    }

    @Test
    void deactivateIsASoftDeleteThatCanBeUndone() {
        int id = drivers.create(body("name", "A", "nic", "1")).id();

        drivers.deactivate(id);
        assertThat(drivers.get(id).active()).isFalse();
        assertThat(drivers.update(id, body("active", true)).active()).isTrue();
        assertThatThrownBy(() -> drivers.deactivate(99)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ roles vs. vehicles

    @Test
    void aPersonShowsTheVehiclesTheyAreAssignedTo() {
        int driver = drivers.create(body("name", "A", "nic", "1")).id();
        int assistant = drivers.create(body("name", "B", "nic", "2", "role", "ASSISTANT")).id();
        vehicles.create(body("registrationNo", "cab-1", "vehicleTypeId", 2, "seatingCapacity", 6, "method", "CONTRACTED",
                "driverId", driver, "assistantId", assistant));
        vehicles.create(body("registrationNo", "cab-2", "vehicleTypeId", 2, "seatingCapacity", 6, "method", "CONTRACTED",
                "driverId", driver));

        assertThat(drivers.get(driver).vehicles()).extracting(v -> v.name()).containsExactly("CAB-1", "CAB-2");
        assertThat(drivers.get(assistant).vehicles()).extracting(v -> v.name()).containsExactly("CAB-1");
    }

    @Test
    void aRoleCannotChangeUnderAVehicleThatUsesIt() {
        int driver = drivers.create(body("name", "A", "nic", "1")).id();
        int spare = drivers.create(body("name", "B", "nic", "2")).id();
        vehicles.create(body("registrationNo", "cab-1", "vehicleTypeId", 2, "seatingCapacity", 6, "method", "CONTRACTED",
                "driverId", driver));

        assertThatThrownBy(() -> drivers.update(driver, body("role", "ASSISTANT")))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("IN_USE");
                    assertThat(e.getMessage()).contains("CAB-1");
                });
        assertThat(drivers.get(driver).role()).isEqualTo("DRIVER");
        assertThat(drivers.update(spare, body("role", "ASSISTANT")).role()).isEqualTo("ASSISTANT");   // unassigned: fine
    }

    // ------------------------------------------------------------------ list

    @Test
    void listFiltersSearchesSortsAndPages() {
        drivers.create(body("name", "Nuwan Chameera", "nic", "831202289V", "contact", "076-758-5347", "licenseNo", "B 2395861", "isChauffeur", true));
        drivers.create(body("name", "Padmalal", "nic", "663254120V", "contact", "077-111-2222", "licenseNo", "B 285182"));
        drivers.create(body("name", "Kamal", "nic", "900000001V", "role", "ASSISTANT"));
        int retired = drivers.create(body("name", "Retired Ravi", "nic", "500000001V")).id();
        drivers.deactivate(retired);

        assertThat(names(drivers.list(null, null, null, null, null, 0, 20)))
                .containsExactly("Kamal", "Nuwan Chameera", "Padmalal", "Retired Ravi");
        assertThat(names(drivers.list("nuwan", null, null, null, null, 0, 20))).containsExactly("Nuwan Chameera");
        assertThat(names(drivers.list("663254", null, null, null, null, 0, 20))).containsExactly("Padmalal");     // NIC
        assertThat(names(drivers.list("077-111", null, null, null, null, 0, 20))).containsExactly("Padmalal");    // phone
        assertThat(names(drivers.list("b 2395861", null, null, null, null, 0, 20))).containsExactly("Nuwan Chameera"); // licence
        assertThat(names(drivers.list(null, "ASSISTANT", null, null, null, 0, 20))).containsExactly("Kamal");
        assertThat(names(drivers.list(null, null, false, null, null, 0, 20))).containsExactly("Retired Ravi");
        assertThat(names(drivers.list(null, null, null, true, null, 0, 20))).containsExactly("Nuwan Chameera");
        assertThat(names(drivers.list(null, null, null, null, List.of("name,desc"), 0, 20)).get(0)).isEqualTo("Retired Ravi");

        PageResponse<DriverResponse> second = drivers.list(null, null, null, null, null, 1, 3);
        assertThat(second.totalElements()).isEqualTo(4);
        assertThat(second.content()).hasSize(1);
        assertThat(second.last()).isTrue();
    }

    @Test
    void searchTreatsPercentAndUnderscoreAsPlainText() {
        drivers.create(body("name", "A", "nic", "1"));

        assertThat(drivers.list("%", null, null, null, null, 0, 20).content()).isEmpty();
        assertThat(drivers.list("_", null, null, null, null, 0, 20).content()).isEmpty();
    }

    @Test
    void listRejectsBadRoleSortAndPaging() {
        assertField(() -> drivers.list(null, "BOSS", null, null, null, 0, 20), "role");
        assertField(() -> drivers.list(null, null, null, null, List.of("password,asc"), 0, 20), "sort");
        assertThatThrownBy(() -> drivers.list(null, null, null, null, null, 0, 0)).isInstanceOf(InvalidRequestException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> names(PageResponse<DriverResponse> page) {
        return page.content().stream().map(DriverResponse::name).toList();
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
