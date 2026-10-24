package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.TestDatabase;
import lk.serenetravels.masterdataservice.dto.GuideResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RefResponse;
import lk.serenetravels.masterdataservice.exception.ApiException;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.impl.GuideServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuideServiceTest {

    private JdbcClient jdbc;
    private GuideServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.fresh();
        service = new GuideServiceImpl(jdbc);
        jdbc.sql("insert into language (name) values ('English'), ('German'), ('Japanese')").update();
    }

    // ------------------------------------------------------------------ create / read

    @Test
    void createNormalisesCodesAndReturnsTheGuide() {
        GuideResponse guide = service.create(body("guideNo", " n-0005 ", "name", "M.A.A. Chandradasa", "guideType", "national_guide",
                "nickname", "Athula", "town", "Dehiwela"));

        assertThat(guide.guideNo()).isEqualTo("N-0005");
        assertThat(guide.guideType()).isEqualTo("NATIONAL_GUIDE");
        assertThat(guide.nickname()).isEqualTo("Athula");
        assertThat(guide.active()).isTrue();
        assertThat(guide.languages()).isEmpty();
    }

    @Test
    void createCanSetTheLanguagesInTheSameCall() {
        GuideResponse guide = service.create(body("guideNo", "N-1", "name", "A", "guideType", "NATIONAL_GUIDE",
                "languageIds", Arrays.asList(2, 1)));

        assertThat(guide.languages()).extracting(RefResponse::name).containsExactly("English", "German");   // language-id order
    }

    @Test
    void createReportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> service.create(body("colour", "red")))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getFieldErrors())
                        .containsKeys("colour", "guideNo", "name", "guideType"));
        assertField(() -> guide("N-1", "guideType", "ASTRONAUT"), "guideType");
        assertField(() -> guide("N-1", "email", "not-an-email"), "email");
        assertField(() -> guide("N-1", "languageIds", "English"), "languageIds");
        assertField(() -> guide("N-1", "languageIds", Arrays.asList(1, 99)), "languageIds[1]");
        assertField(() -> guide("N-1", "languageIds", Arrays.asList(1, 1)), "languageIds[1]");
        assertField(() -> guide("N-1", "languageIds", Arrays.asList("x")), "languageIds[0]");
        assertThat(jdbc.sql("select count(*) from guide").query(Long.class).single()).isZero();   // nothing half-saved
    }

    @Test
    void aGuideNumberAndANicBelongToOneGuide() {
        guide("N-1", "nic", "831202289V");

        assertThatThrownBy(() -> guide("n-1"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        assertThatThrownBy(() -> guide("N-2", "nic", " 831202289v "))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        guide("N-3");     // no NIC at all is fine, and can repeat
        guide("N-4");
    }

    @Test
    void getAnUnknownGuideIs404() {
        assertThatThrownBy(() -> service.get(99)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ update / deactivate

    @Test
    void updateChangesOnlyTheGivenFieldsAndCanClearOptionalOnes() {
        int id = guide("N-1", "contact", "0777490642", "town", "Kandy").id();

        GuideResponse renamed = service.update(id, body("name", "Renamed"));
        assertThat(renamed.name()).isEqualTo("Renamed");
        assertThat(renamed.contact()).isEqualTo("0777490642");

        GuideResponse cleared = service.update(id, body("contact", null, "town", null));
        assertThat(cleared.contact()).isNull();
        assertThat(cleared.town()).isNull();
        assertThat(cleared.name()).isEqualTo("Renamed");
    }

    @Test
    void updateRejectsEmptyBodiesNullFlagsAndTheOtherGuidesNumbers() {
        int first = guide("N-1", "nic", "111").id();
        guide("N-2", "nic", "222");

        assertThatThrownBy(() -> service.update(first, Map.of())).isInstanceOf(InvalidRequestException.class);
        assertField(() -> service.update(first, body("active", null)), "active");
        assertField(() -> service.update(first, body("guideType", null)), "guideType");
        assertField(() -> service.update(first, body("email", "nope")), "email");
        assertThatThrownBy(() -> service.update(first, body("guideNo", "n-2")))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        assertThatThrownBy(() -> service.update(first, body("nic", "222")))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        assertThat(service.update(first, body("guideNo", "n-1", "nic", "111")).guideNo()).isEqualTo("N-1");   // own values are fine
        assertThatThrownBy(() -> service.update(99, body("name", "x"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateDoesNotTouchTheLanguages() {
        int id = guide("N-1", "languageIds", List.of(1, 2)).id();

        assertThat(service.update(id, body("name", "Renamed")).languages()).hasSize(2);
        assertField(() -> service.update(id, body("languageIds", List.of(1))), "languageIds");   // not an update field
    }

    @Test
    void deactivateIsASoftDeleteThatCanBeUndone() {
        int id = guide("N-1").id();

        service.deactivate(id);
        assertThat(service.get(id).active()).isFalse();
        assertThat(service.update(id, body("active", true)).active()).isTrue();
        assertThatThrownBy(() -> service.deactivate(99)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ languages

    @Test
    void languagesAreReplacedAsAWholeSet() {
        int id = guide("N-1").id();

        assertThat(service.replaceLanguages(id, List.of(3, 1))).extracting(RefResponse::name).containsExactly("English", "Japanese");
        assertThat(service.replaceLanguages(id, List.of(2))).extracting(RefResponse::name).containsExactly("German");
        assertThat(service.get(id).languages()).extracting(RefResponse::name).containsExactly("German");
        assertThat(service.replaceLanguages(id, List.of())).isEmpty();
        assertThat(service.get(id).languages()).isEmpty();
    }

    @Test
    void aRefusedLanguageChangeLeavesTheOldOnesInPlace() {
        int id = guide("N-1", "languageIds", List.of(1, 2)).id();

        assertField(() -> service.replaceLanguages(id, Arrays.asList(1, 99)), "languageIds[1]");
        assertField(() -> service.replaceLanguages(id, List.of(3, 3)), "languageIds[1]");
        assertField(() -> service.replaceLanguages(id, Arrays.asList(1, null)), "languageIds[1]");
        assertThatThrownBy(() -> service.replaceLanguages(id, null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.replaceLanguages(99, List.of(1))).isInstanceOf(NotFoundException.class);
        assertThat(service.get(id).languages()).extracting(RefResponse::name).containsExactly("English", "German");
    }

    // ------------------------------------------------------------------ list

    @Test
    void listFiltersSearchesSortsAndPages() {
        guide("N-1", "name", "Chandradasa", "nickname", "Athula", "town", "Dehiwela", "nic", "111", "email", "athula@x.lk", "languageIds", List.of(1, 2));
        guide("N-2", "name", "Tebel", "nickname", "Kithsiri", "town", "Panadura", "contact", "076-982-5635", "languageIds", List.of(1));
        guide("C-1", "name", "Weerasinghe", "guideType", "CHAUFFEUR_GUIDE", "town", "Veyangoda", "languageIds", List.of(3));
        int retired = guide("N-3", "name", "Retired").id();
        service.deactivate(retired);

        assertThat(names(service.list(null, null, null, null, null, 0, 20)))
                .containsExactly("Chandradasa", "Retired", "Tebel", "Weerasinghe");
        assertThat(names(service.list("athula", null, null, null, null, 0, 20))).containsExactly("Chandradasa");     // nickname
        assertThat(names(service.list("n-2", null, null, null, null, 0, 20))).containsExactly("Tebel");               // guide no
        assertThat(names(service.list("111", null, null, null, null, 0, 20))).containsExactly("Chandradasa");         // NIC
        assertThat(names(service.list("982-5635", null, null, null, null, 0, 20))).containsExactly("Tebel");          // phone
        assertThat(names(service.list("@x.lk", null, null, null, null, 0, 20))).containsExactly("Chandradasa");       // email
        assertThat(names(service.list("veyang", null, null, null, null, 0, 20))).containsExactly("Weerasinghe");      // town
        assertThat(names(service.list(null, "CHAUFFEUR_GUIDE", null, null, null, 0, 20))).containsExactly("Weerasinghe");
        assertThat(names(service.list(null, null, 1, null, null, 0, 20))).containsExactly("Chandradasa", "Tebel");    // speaks English
        assertThat(names(service.list(null, null, 3, null, null, 0, 20))).containsExactly("Weerasinghe");
        assertThat(names(service.list(null, null, null, false, null, 0, 20))).containsExactly("Retired");
        assertThat(names(service.list(null, null, null, null, List.of("town,desc"), 0, 20)).get(0)).isEqualTo("Weerasinghe");
        assertThat(names(service.list(null, null, null, null, List.of("guideNo,asc"), 0, 20)).get(0)).isEqualTo("Weerasinghe"); // C-1

        PageResponse<GuideResponse> second = service.list(null, null, null, null, null, 1, 3);
        assertThat(second.totalElements()).isEqualTo(4);
        assertThat(second.content()).hasSize(1);
        assertThat(second.last()).isTrue();
        // every row of a page carries its languages
        assertThat(service.list(null, null, null, null, List.of("name,asc"), 0, 20).content().get(0).languages()).hasSize(2);
    }

    @Test
    void searchTreatsPercentAndUnderscoreAsPlainText() {
        guide("N-1");

        assertThat(service.list("%", null, null, null, null, 0, 20).content()).isEmpty();
        assertThat(service.list("_", null, null, null, null, 0, 20).content()).isEmpty();
    }

    @Test
    void listRejectsBadSortGuideTypeAndPaging() {
        assertField(() -> service.list(null, null, null, null, List.of("password,asc"), 0, 20), "sort");
        assertField(() -> service.list(null, "ASTRONAUT", null, null, null, 0, 20), "guideType");
        assertThatThrownBy(() -> service.list(null, null, null, null, null, 0, 0)).isInstanceOf(InvalidRequestException.class);
    }

    // ------------------------------------------------------------------ helpers

    // A valid National guide by default; extra key/value pairs override or add fields.
    private GuideResponse guide(String guideNo, Object... overrides) {
        Map<String, Object> body = body("guideNo", guideNo, "name", "Guide " + guideNo, "guideType", "NATIONAL_GUIDE");
        for (int i = 0; i < overrides.length; i += 2) {
            body.put((String) overrides[i], overrides[i + 1]);
        }
        return service.create(body);
    }

    private static List<String> names(PageResponse<GuideResponse> page) {
        return page.content().stream().map(GuideResponse::name).toList();
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
