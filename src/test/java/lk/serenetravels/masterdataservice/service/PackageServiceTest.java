package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.TestDatabase;
import lk.serenetravels.masterdataservice.dto.PackageResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RateUnit;
import lk.serenetravels.masterdataservice.dto.RateWriteRequest;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.exception.ApiException;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.impl.PackageServiceImpl;
import lk.serenetravels.masterdataservice.service.impl.RateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackageServiceTest {

    private JdbcClient jdbc;
    private RateServiceImpl rates;
    private PackageServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.fresh();
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneId.of("Asia/Colombo"));
        rates = new RateServiceImpl(jdbc, clock);
        service = new PackageServiceImpl(jdbc, clock);
        jdbc.sql("insert into destination (name, country) values ('Sigiriya', 'Sri Lanka'), ('Kandy', 'Sri Lanka')").update();
        // ids 1..4: Sigiriya Rock, Pidurangala, Temple of the Tooth, Royal Botanical Gardens
        jdbc.sql("""
                insert into attraction (destination_id, name, pricing_method) values
                (1, 'Sigiriya Rock', 'PER_PERSON'), (1, 'Pidurangala', 'PER_PERSON'),
                (2, 'Temple of the Tooth', 'PER_PERSON'), (2, 'Royal Botanical Gardens', 'PER_PERSON')""").update();
    }

    // ------------------------------------------------------------------ create / read

    @Test
    void createReturnsThePackageWithItsSitesByNameAndNoPriceYet() {
        PackageResponse created = service.create(body("name", "Cultural Triangle", "attractionIds", List.of(3, 1)));

        assertThat(created.id()).isNotNull();
        assertThat(created.sites()).extracting(RefResponse::name).containsExactly("Sigiriya Rock", "Temple of the Tooth");
        assertThat(created.currentRate()).isNull();
    }

    @Test
    void aPackageCanBeCreatedWithNoSites() {
        assertThat(service.create(body("name", "Empty")).sites()).isEmpty();
    }

    @Test
    void createReportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> service.create(body("colour", "red")))
                .isInstanceOfSatisfying(InvalidRequestException.class,
                        e -> assertThat(e.getFieldErrors()).containsKeys("colour", "name"));
        assertField(() -> service.create(body("name", "X", "attractionIds", List.of(1, 99))), "attractionIds[1]");
        assertField(() -> service.create(body("name", "X", "attractionIds", List.of(2, 2))), "attractionIds[1]");
        assertField(() -> service.create(body("name", "X", "attractionIds", Arrays.asList(1, null))), "attractionIds[1]");
        assertField(() -> service.create(body("name", "X", "attractionIds", "1")), "attractionIds");
        assertThat(jdbc.sql("select count(*) from site_tour_package").query(Long.class).single()).isZero();   // nothing half-saved
    }

    @Test
    void aNameIsUniqueAcrossPackagesIgnoringCase() {
        pkg("Cultural Triangle");

        assertThatThrownBy(() -> pkg("cultural triangle"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
    }

    @Test
    void getAnUnknownPackageIs404() {
        assertThatThrownBy(() -> service.get(99, null)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ prices

    @Test
    void thePriceShownIsTheOneInForceOnTheRequestedDate() {
        int id = pkg("Cultural Triangle").id();
        rates.setRate("package", rate(id, "100", null), "t");           // from today, 2026-10-18
        rates.setRate("package", rate(id, "120", "2026-12-01"), "t");   // closes the first on 2026-12-01

        assertThat(service.get(id, null).currentRate().rate()).isEqualByComparingTo("100");
        assertThat(service.get(id, null).currentRate().unit()).isEqualTo(RateUnit.USD);
        assertThat(service.get(id, LocalDate.parse("2026-11-30")).currentRate().rate()).isEqualByComparingTo("100");
        assertThat(service.get(id, LocalDate.parse("2026-12-01")).currentRate().rate()).isEqualByComparingTo("120");   // half-open
        assertThat(service.get(id, LocalDate.parse("2026-01-01")).currentRate()).isNull();                             // before any price
        assertThat(service.list(null, null, LocalDate.parse("2027-01-01"), null, 0, 20).content().get(0).currentRate().rate())
                .isEqualByComparingTo("120");
    }

    // ------------------------------------------------------------------ update / delete

    @Test
    void updateRenamesAndLeavesTheSitesAlone() {
        int id = service.create(body("name", "Cultural Triangle", "attractionIds", List.of(1))).id();

        PackageResponse renamed = service.update(id, body("name", "Cultural Triangle Plus"));

        assertThat(renamed.name()).isEqualTo("Cultural Triangle Plus");
        assertThat(renamed.sites()).extracting(RefResponse::name).containsExactly("Sigiriya Rock");
    }

    @Test
    void updateRejectsEmptyBodiesUnknownFieldsClashesAndUnknownIds() {
        int first = pkg("Cultural Triangle").id();
        pkg("Hill Country");

        assertThatThrownBy(() -> service.update(first, Map.of())).isInstanceOf(InvalidRequestException.class);
        assertField(() -> service.update(first, body("attractionIds", List.of(1))), "attractionIds");   // has its own call
        assertField(() -> service.update(first, body("name", null)), "name");
        assertField(() -> service.update(first, body("name", "  ")), "name");
        assertThatThrownBy(() -> service.update(first, body("name", "hill country")))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        assertThat(service.update(first, body("name", "cultural triangle")).name()).isEqualTo("cultural triangle");   // own name is fine
        assertThatThrownBy(() -> service.update(99, body("name", "x"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteRemovesAnUnpricedPackageAndItsSiteLinks() {
        int id = service.create(body("name", "Cultural Triangle", "attractionIds", List.of(1, 2))).id();

        service.delete(id);

        assertThatThrownBy(() -> service.get(id, null)).isInstanceOf(NotFoundException.class);
        assertThat(jdbc.sql("select count(*) from site_tour_package_site").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from attraction").query(Long.class).single()).isEqualTo(4);   // the sites live on
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aPackageWithPriceHistoryCannotBeDeleted() {
        int id = service.create(body("name", "Cultural Triangle", "attractionIds", List.of(1))).id();
        rates.setRate("package", rate(id, "100", null), "t");

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("IN_USE"));
        assertThat(service.get(id, null).sites()).hasSize(1);   // refused before anything was removed
    }

    // ------------------------------------------------------------------ sites

    @Test
    void sitesAreReplacedAsAWholeSetAndReturnedByName() {
        int id = pkg("Cultural Triangle").id();

        assertThat(service.replaceSites(id, List.of(4, 2, 1))).extracting(RefResponse::name)
                .containsExactly("Pidurangala", "Royal Botanical Gardens", "Sigiriya Rock");
        assertThat(service.replaceSites(id, List.of(3))).extracting(RefResponse::name).containsExactly("Temple of the Tooth");
        assertThat(service.get(id, null).sites()).extracting(RefResponse::name).containsExactly("Temple of the Tooth");
        assertThat(service.replaceSites(id, List.of())).isEmpty();
        assertThat(service.get(id, null).sites()).isEmpty();
    }

    @Test
    void aRefusedSitesChangeLeavesTheOldOnesInPlace() {
        int id = service.create(body("name", "Cultural Triangle", "attractionIds", List.of(1, 2))).id();

        assertField(() -> service.replaceSites(id, Arrays.asList(1, 99)), "attractionIds[1]");
        assertField(() -> service.replaceSites(id, List.of(3, 3)), "attractionIds[1]");
        assertThatThrownBy(() -> service.replaceSites(id, null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.replaceSites(99, List.of(1))).isInstanceOf(NotFoundException.class);
        assertThat(service.get(id, null).sites()).extracting(RefResponse::name).containsExactly("Pidurangala", "Sigiriya Rock");
    }

    @Test
    void aSiteCanBeOnSeveralPackages() {
        int a = service.create(body("name", "A", "attractionIds", List.of(1))).id();
        int b = service.create(body("name", "B", "attractionIds", List.of(1))).id();

        assertThat(service.get(a, null).sites()).hasSize(1);
        assertThat(service.get(b, null).sites()).hasSize(1);
    }

    // ------------------------------------------------------------------ list

    @Test
    void listFiltersSearchesSortsAndPages() {
        int cultural = service.create(body("name", "Cultural Triangle", "attractionIds", List.of(1, 2, 3))).id();
        service.create(body("name", "Hill Country", "attractionIds", List.of(3, 4)));
        service.create(body("name", "Beach Break"));
        rates.setRate("package", rate(cultural, "100", null), "t");

        assertThat(names(service.list(null, null, null, null, 0, 20))).containsExactly("Beach Break", "Cultural Triangle", "Hill Country");
        assertThat(names(service.list("HILL", null, null, null, 0, 20))).containsExactly("Hill Country");
        assertThat(names(service.list(null, 3, null, null, 0, 20))).containsExactly("Cultural Triangle", "Hill Country");   // includes the Temple
        assertThat(names(service.list(null, 4, null, null, 0, 20))).containsExactly("Hill Country");
        assertThat(names(service.list(null, null, null, List.of("name,desc"), 0, 20))).containsExactly("Hill Country", "Cultural Triangle", "Beach Break");

        PageResponse<PackageResponse> second = service.list(null, null, null, null, 1, 2);
        assertThat(second.totalElements()).isEqualTo(3);
        assertThat(second.content()).hasSize(1);
        assertThat(second.last()).isTrue();
        // every row of a page carries its sites and price
        PackageResponse first = service.list("cultural", null, null, null, 0, 20).content().get(0);
        assertThat(first.sites()).hasSize(3);
        assertThat(first.currentRate().rate()).isEqualByComparingTo("100");
        assertThat(service.list("beach", null, null, null, 0, 20).content().get(0).currentRate()).isNull();
    }

    @Test
    void searchTreatsPercentAndUnderscoreAsPlainText() {
        pkg("Cultural Triangle");

        assertThat(service.list("%", null, null, null, 0, 20).content()).isEmpty();
        assertThat(service.list("_", null, null, null, 0, 20).content()).isEmpty();
    }

    @Test
    void listRejectsBadSortAndPaging() {
        assertField(() -> service.list(null, null, null, List.of("password,asc"), 0, 20), "sort");
        assertField(() -> service.list(null, null, null, List.of("name,sideways"), 0, 20), "sort");
        assertThatThrownBy(() -> service.list(null, null, null, null, 0, 0)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.list(null, null, null, null, -1, 20)).isInstanceOf(InvalidRequestException.class);
    }

    // ------------------------------------------------------------------ helpers

    private PackageResponse pkg(String name) {
        return service.create(body("name", name));
    }

    private static RateWriteRequest rate(int packageId, String amount, String from) {
        return new RateWriteRequest(Map.of("packageId", packageId), new BigDecimal(amount), from == null ? null : LocalDate.parse(from));
    }

    private static List<String> names(PageResponse<PackageResponse> page) {
        return page.content().stream().map(PackageResponse::name).toList();
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
