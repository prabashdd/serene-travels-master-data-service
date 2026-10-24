package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.TestDatabase;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.VehicleResponse;
import lk.serenetravels.masterdataservice.dto.VehicleWarning;
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

class VehicleServiceTest {

    private JdbcClient jdbc;
    private DriverServiceImpl drivers;
    private VehicleServiceImpl service;
    private int driverId;
    private int assistantId;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneId.of("Asia/Colombo"));   // today = 2026-10-18
        drivers = new DriverServiceImpl(jdbc, clock);
        service = new VehicleServiceImpl(jdbc, clock);
        jdbc.sql("insert into vehicle_type (name, min_capacity, max_capacity) values ('Car', 1, 2), ('Van', 3, 9), ('Mini Coach', 9, 14)").update();
        driverId = drivers.create(body("name", "Nuwan", "nic", "1")).id();
        assistantId = drivers.create(body("name", "Kamal", "nic", "2", "role", "ASSISTANT")).id();
    }

    // ------------------------------------------------------------------ create / read

    @Test
    void createNormalisesTheRegistrationAndReturnsTheFullVehicle() {
        VehicleResponse vehicle = service.create(body("registrationNo", " cab-3827 ", "vehicleTypeId", 1, "seatingCapacity", 2,
                "method", "contracted", "ownerName", "Anura", "driverId", driverId));

        assertThat(vehicle.registrationNo()).isEqualTo("CAB-3827");
        assertThat(vehicle.method()).isEqualTo("CONTRACTED");
        assertThat(vehicle.vehicleType().name()).isEqualTo("Car");
        assertThat(vehicle.driver().name()).isEqualTo("Nuwan");
        assertThat(vehicle.assistant()).isNull();
        assertThat(vehicle.active()).isTrue();
        assertThat(vehicle.warnings()).isEmpty();
    }

    @Test
    void createReportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> service.create(body("colour", "red")))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getFieldErrors())
                        .containsKeys("colour", "registrationNo", "vehicleTypeId", "seatingCapacity", "method"));
        assertField(() -> car("X-1", "method", "LEASED"), "method");
        assertField(() -> car("X-1", "vehicleTypeId", 99), "vehicleTypeId");
        assertField(() -> car("X-1", "seatingCapacity", 0), "seatingCapacity");
        assertField(() -> car("X-1", "insuranceExpiryDate", "18/10/2026"), "insuranceExpiryDate");
    }

    @Test
    void seatsCannotExceedWhatTheVehicleTypeCarries() {
        assertField(() -> car("X-1", "seatingCapacity", 4), "seatingCapacity");          // a Car carries at most 2
        assertThat(service.create(body("registrationNo", "X-2", "vehicleTypeId", 2, "seatingCapacity", 9,
                "method", "INTERNAL")).seatingCapacity()).isEqualTo(9);
    }

    @Test
    void thePeopleAssignedMustBeActiveAndHoldTheRightRole() {
        assertField(() -> car("X-1", "driverId", assistantId), "driverId");          // an ASSISTANT cannot drive
        assertField(() -> car("X-1", "assistantId", driverId), "assistantId");       // a DRIVER cannot be the assistant
        assertField(() -> car("X-1", "driverId", 99), "driverId");
        drivers.deactivate(driverId);
        assertField(() -> car("X-1", "driverId", driverId), "driverId");             // inactive
        assertThat(car("X-2", "assistantId", assistantId).assistant().name()).isEqualTo("Kamal");
    }

    @Test
    void aRegistrationIsUniqueWhateverTheCase() {
        car("CAB-1");

        assertThatThrownBy(() -> car("cab-1"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
    }

    @Test
    void getAnUnknownVehicleIs404() {
        assertThatThrownBy(() -> service.get(99)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ update

    @Test
    void updateChangesOnlyTheGivenFieldsAndCanClearOptionalOnes() {
        int id = car("CAB-1", "ownerName", "Anura", "assistantId", assistantId, "insuranceExpiryDate", "2027-01-01").id();

        VehicleResponse renewed = service.update(id, body("insuranceExpiryDate", "2028-01-01"));
        assertThat(renewed.insuranceExpiryDate()).isEqualTo(LocalDate.of(2028, 1, 1));
        assertThat(renewed.ownerName()).isEqualTo("Anura");
        assertThat(renewed.assistant().name()).isEqualTo("Kamal");

        VehicleResponse cleared = service.update(id, body("assistantId", null, "insuranceExpiryDate", null));
        assertThat(cleared.assistant()).isNull();
        assertThat(cleared.insuranceExpiryDate()).isNull();
    }

    @Test
    void anUpdateIsCheckedAgainstTheRowAsItWillLook() {
        int id = car("CAB-1", "seatingCapacity", 2).id();

        // Moving a 2-seater into the Van type is fine; moving a 6-seater back to Car is not.
        VehicleResponse van = service.update(id, body("vehicleTypeId", 2, "seatingCapacity", 6));
        assertThat(van.vehicleType().name()).isEqualTo("Van");
        assertField(() -> service.update(id, body("vehicleTypeId", 1)), "seatingCapacity");
    }

    @Test
    void anUpdateDoesNotTripOverAnAlreadyInactiveDriverItIsNotChanging() {
        int id = car("CAB-1", "driverId", driverId).id();
        drivers.deactivate(driverId);

        // Renewing the licence must work even though the assigned driver has since been deactivated.
        assertThat(service.update(id, body("revenueLicenseExpiryDate", "2027-05-01")).revenueLicenseExpiryDate())
                .isEqualTo(LocalDate.of(2027, 5, 1));
    }

    @Test
    void updateRejectsEmptyBodiesNullFlagsAndTheOtherVehiclesRegistration() {
        int first = car("CAB-1").id();
        car("CAB-2");

        assertThatThrownBy(() -> service.update(first, Map.of())).isInstanceOf(InvalidRequestException.class);
        assertField(() -> service.update(first, body("active", null)), "active");
        assertField(() -> service.update(first, body("method", null)), "method");
        assertThatThrownBy(() -> service.update(first, body("registrationNo", "cab-2")))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        assertThat(service.update(first, body("registrationNo", "cab-1")).registrationNo()).isEqualTo("CAB-1");
        assertThatThrownBy(() -> service.update(99, body("ownerName", "x"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deactivateTakesTheVehicleOutOfServiceAndCanBeUndone() {
        int id = car("CAB-1").id();

        service.deactivate(id);
        assertThat(service.get(id).active()).isFalse();
        assertThat(service.update(id, body("active", true)).active()).isTrue();
        assertThatThrownBy(() -> service.deactivate(99)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ warnings

    @Test
    void expiryWarningsAreSignedByDaysLeft() {
        VehicleResponse expired = car("CAB-1", "driverId", driverId, "insuranceExpiryDate", "2026-10-08");
        VehicleResponse expiring = car("CAB-2", "driverId", driverId, "insuranceExpiryDate", "2026-11-10",
                "revenueLicenseExpiryDate", "2026-10-18");
        VehicleResponse fine = car("CAB-3", "driverId", driverId, "insuranceExpiryDate", "2027-06-01");

        assertThat(codes(expired)).containsExactly("INSURANCE_EXPIRED=-10");
        assertThat(codes(expiring)).containsExactly("INSURANCE_EXPIRING=23", "LICENSE_EXPIRING=0");   // expires today
        assertThat(fine.warnings()).isEmpty();
    }

    @Test
    void aMissingOrInactiveDriverIsFlaggedButOnlyOnVehiclesInService() {
        int noDriver = car("CAB-1").id();
        int withDriver = car("CAB-2", "driverId", driverId, "assistantId", assistantId).id();
        assertThat(codes(service.get(noDriver))).containsExactly("NO_DRIVER=null");

        drivers.deactivate(driverId);
        drivers.deactivate(assistantId);
        assertThat(codes(service.get(withDriver))).containsExactly("DRIVER_INACTIVE=null", "ASSISTANT_INACTIVE=null");

        service.deactivate(noDriver);
        assertThat(service.get(noDriver).warnings()).isEmpty();   // out of service: nothing to chase
    }

    // ------------------------------------------------------------------ list

    @Test
    void listFiltersSearchesSortsAndPages() {
        car("CAB-1", "ownerName", "Anura Lokuhetty", "insuranceExpiryDate", "2026-12-01");
        car("PG-4183", "vehicleTypeId", 2, "seatingCapacity", 4, "ownerName", "Anura Lokuhetty", "insuranceExpiryDate", "2026-10-01");
        car("PD-2668", "vehicleTypeId", 3, "seatingCapacity", 10, "method", "INTERNAL", "ownerName", "Company");
        int retired = car("OLD-1").id();
        service.deactivate(retired);

        assertThat(regs(service.list(null, null, null, null, null, null, 0, 20))).containsExactly("CAB-1", "OLD-1", "PD-2668", "PG-4183");
        assertThat(regs(service.list("lokuhetty", null, null, null, null, null, 0, 20))).containsExactly("CAB-1", "PG-4183");
        assertThat(regs(service.list("pd-26", null, null, null, null, null, 0, 20))).containsExactly("PD-2668");
        assertThat(regs(service.list(null, 2, null, null, null, null, 0, 20))).containsExactly("PG-4183");
        assertThat(regs(service.list(null, null, "INTERNAL", null, null, null, 0, 20))).containsExactly("PD-2668");
        assertThat(regs(service.list(null, null, null, false, null, null, 0, 20))).containsExactly("OLD-1");
        assertThat(regs(service.list(null, null, null, null, null, List.of("type,desc", "registrationNo,asc"), 0, 20)).get(0)).isEqualTo("PG-4183");   // Van sorts after Mini Coach

        PageResponse<VehicleResponse> second = service.list(null, null, null, null, null, null, 1, 3);
        assertThat(second.totalElements()).isEqualTo(4);
        assertThat(second.content()).hasSize(1);
        assertThat(second.last()).isTrue();
    }

    @Test
    void expiringWithinDaysIncludesAlreadyExpiredAndChecksBothDocuments() {
        car("EXPIRED", "insuranceExpiryDate", "2026-09-01");
        car("SOON", "revenueLicenseExpiryDate", "2026-11-05");           // 18 days away, on the licence
        car("LATER", "insuranceExpiryDate", "2027-03-01", "revenueLicenseExpiryDate", "2027-03-01");
        car("UNDATED");

        assertThat(regs(service.list(null, null, null, null, 30, List.of("registrationNo,asc"), 0, 20)))
                .containsExactly("EXPIRED", "SOON");
        assertThat(regs(service.list(null, null, null, null, 0, List.of("registrationNo,asc"), 0, 20))).containsExactly("EXPIRED");
        assertThat(regs(service.list(null, null, null, null, 365, List.of("registrationNo,asc"), 0, 20)))
                .containsExactly("EXPIRED", "LATER", "SOON");
    }

    @Test
    void searchTreatsPercentAndUnderscoreAsPlainText() {
        car("CAB-1");

        assertThat(service.list("%", null, null, null, null, null, 0, 20).content()).isEmpty();
        assertThat(service.list("_", null, null, null, null, null, 0, 20).content()).isEmpty();
    }

    @Test
    void listRejectsBadSortMethodExpiryAndPaging() {
        assertField(() -> service.list(null, null, null, null, null, List.of("password,asc"), 0, 20), "sort");
        assertField(() -> service.list(null, null, "LEASED", null, null, null, 0, 20), "method");
        assertField(() -> service.list(null, null, null, null, -1, null, 0, 20), "expiringWithinDays");
        assertThatThrownBy(() -> service.list(null, null, null, null, null, null, 0, 0)).isInstanceOf(InvalidRequestException.class);
    }

    // ------------------------------------------------------------------ helpers

    // A valid Car by default; extra key/value pairs override or add fields.
    private VehicleResponse car(String registration, Object... overrides) {
        Map<String, Object> body = body("registrationNo", registration, "vehicleTypeId", 1, "seatingCapacity", 2, "method", "CONTRACTED");
        for (int i = 0; i < overrides.length; i += 2) {
            body.put((String) overrides[i], overrides[i + 1]);
        }
        return service.create(body);
    }

    private static List<String> regs(PageResponse<VehicleResponse> page) {
        return page.content().stream().map(VehicleResponse::registrationNo).toList();
    }

    private static List<String> codes(VehicleResponse vehicle) {
        return vehicle.warnings().stream().map((VehicleWarning w) -> w.code() + "=" + w.daysLeft()).toList();
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
