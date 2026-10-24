package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.TestDatabase;
import lk.serenetravels.masterdataservice.dto.InfoArticleResponse;
import lk.serenetravels.masterdataservice.dto.InfoArticleSummaryResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.exception.ApiException;
import lk.serenetravels.masterdataservice.exception.ConflictException;
import lk.serenetravels.masterdataservice.exception.InvalidRequestException;
import lk.serenetravels.masterdataservice.exception.NotFoundException;
import lk.serenetravels.masterdataservice.service.impl.InfoArticleServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InfoArticleServiceTest {

    private JdbcClient jdbc;
    private InfoArticleServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.fresh();
        service = new InfoArticleServiceImpl(jdbc);
        jdbc.sql("insert into destination (name, country) values ('Kandy', 'Sri Lanka'), ('Galle', 'Sri Lanka')").update();
    }

    // ------------------------------------------------------------------ create / read

    @Test
    void createReturnsTheArticleWithItsDestinationAndFullBody() {
        InfoArticleResponse created = service.create(body("category", "general", "destinationId", 1, "title", "Kandy",
                "body", "KANDY\nThe hill capital.\nHome of the Temple of the Tooth."));

        assertThat(created.id()).isNotNull();
        assertThat(created.category()).isEqualTo("GENERAL");
        assertThat(created.destination().name()).isEqualTo("Kandy");
        assertThat(created.body()).isEqualTo("KANDY\nThe hill capital.\nHome of the Temple of the Tooth.");   // line breaks kept
    }

    @Test
    void anArticleWithoutADestinationIsCountryWide() {
        InfoArticleResponse visa = service.create(body("category", "VISA", "title", "Entry rules", "body", "Apply for an ETA online."));

        assertThat(visa.destination()).isNull();
        assertThat(service.get(visa.id()).destination()).isNull();
    }

    @Test
    void createReportsEveryProblemAtOnce() {
        assertThatThrownBy(() -> service.create(body("colour", "red")))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getFieldErrors())
                        .containsKeys("colour", "category", "title", "body"));
        assertThatThrownBy(() -> service.create(body("category", "GOSSIP", "destinationId", 99, "title", "x", "body", "y")))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getFieldErrors())
                        .containsKeys("category", "destinationId"));
        assertField(() -> service.create(body("category", "GENERAL", "title", "t".repeat(201), "body", "y")), "title");
        assertField(() -> service.create(body("category", "GENERAL", "title", "t", "body", "b".repeat(20_001))), "body");
        assertField(() -> service.create(body("category", "GENERAL", "title", " ", "body", "y")), "title");
        assertThat(jdbc.sql("select count(*) from info_article").query(Long.class).single()).isZero();   // nothing half-saved
    }

    @Test
    void theSameTitleInTheSameCategoryAndDestinationIsADuplicate() {
        article("TRAVEL_TIP", 1, "Packing");

        assertThatThrownBy(() -> article("travel_tip", 1, "PACKING"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        // another destination, another category, or country-wide are all different articles
        assertThat(article("TRAVEL_TIP", 2, "Packing").id()).isNotNull();
        assertThat(article("GENERAL", 1, "Packing").id()).isNotNull();
        assertThat(article("TRAVEL_TIP", null, "Packing").id()).isNotNull();
        assertThatThrownBy(() -> article("TRAVEL_TIP", null, "packing")).isInstanceOf(ConflictException.class);   // two country-wide
    }

    @Test
    void getAnUnknownArticleIs404() {
        assertThatThrownBy(() -> service.get(99)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ update / delete

    @Test
    void updateChangesOnlyWhatWasSent() {
        int id = article("GENERAL", 1, "Kandy").id();

        InfoArticleResponse reworded = service.update(id, body("body", "A new write-up."));
        assertThat(reworded.body()).isEqualTo("A new write-up.");
        assertThat(reworded.title()).isEqualTo("Kandy");
        assertThat(reworded.destination().name()).isEqualTo("Kandy");

        assertThat(service.update(id, body("destinationId", 2)).destination().name()).isEqualTo("Galle");
        assertThat(service.update(id, body("destinationId", null)).destination()).isNull();       // now country-wide
        assertThat(service.update(id, body("category", "weather")).category()).isEqualTo("WEATHER");
    }

    @Test
    void updateRejectsEmptyBodiesBadValuesClashesAndUnknownIds() {
        int first = article("GENERAL", 1, "Kandy").id();
        article("GENERAL", 2, "Kandy");

        assertThatThrownBy(() -> service.update(first, Map.of())).isInstanceOf(InvalidRequestException.class);
        assertField(() -> service.update(first, body("category", "GOSSIP")), "category");
        assertField(() -> service.update(first, body("destinationId", 99)), "destinationId");
        assertField(() -> service.update(first, body("title", null)), "title");
        assertField(() -> service.update(first, body("colour", "red")), "colour");
        assertThatThrownBy(() -> service.update(first, body("destinationId", 2)))                 // Galle already has GENERAL "Kandy"
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getCode()).isEqualTo("DUPLICATE"));
        assertThat(service.update(first, body("title", "kandy")).title()).isEqualTo("kandy");     // own title is fine
        assertThatThrownBy(() -> service.update(99, body("title", "x"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteRemovesTheArticle() {
        int id = article("GENERAL", 1, "Kandy").id();

        service.delete(id);

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ list

    @Test
    void listRowsCarryAnExcerptAndBodyLengthButNotTheBody() {
        String text = "TANGALLE\nTranquil sandy beaches with a surf ideal for swimming, snorkeling and surfing, "
                + "a fishing harbour and an old fort town further along the coast road towards the south.";
        service.create(body("category", "GENERAL", "destinationId", 1, "title", "Tangalle", "body", text));
        service.create(body("category", "GENERAL", "destinationId", 2, "title", "Short", "body", "Just one line."));

        List<InfoArticleSummaryResponse> rows = service.list(null, null, null, false, null, List.of("title,desc"), 0, 20).content();
        InfoArticleSummaryResponse tangalle = rows.get(0);
        assertThat(tangalle.title()).isEqualTo("Tangalle");
        assertThat(tangalle.bodyLength()).isEqualTo(text.length());
        assertThat(tangalle.excerpt()).startsWith("Tranquil sandy beaches")          // the capitalised name line is skipped
                .endsWith("…").hasSizeLessThanOrEqualTo(141).doesNotContain("\n");
        assertThat(rows.get(1).excerpt()).isEqualTo("Just one line.");
    }

    @Test
    void listFiltersSearchesSortsAndPages() {
        article("GENERAL", 1, "Kandy");
        article("GENERAL", 2, "Galle");
        article("TRAVEL_TIP", 1, "Packing for the hills");
        article("VISA", null, "Entry rules");
        service.create(body("category", "WEATHER", "title", "Monsoon", "body", "Rain on the south-west coast from May."));

        assertThat(titles(service.list(null, null, null, false, null, null, 0, 20)))
                .containsExactly("Entry rules", "Galle", "Kandy", "Monsoon", "Packing for the hills");
        assertThat(titles(service.list("PACKING", null, null, false, null, null, 0, 20))).containsExactly("Packing for the hills");
        assertThat(titles(service.list("south-west", null, null, false, null, null, 0, 20))).containsExactly("Monsoon");   // body search
        assertThat(titles(service.list(null, "travel_tip", null, false, null, null, 0, 20))).containsExactly("Packing for the hills");
        assertThat(titles(service.list(null, null, 1, false, null, null, 0, 20))).containsExactly("Kandy", "Packing for the hills");
        assertThat(titles(service.list(null, null, 1, true, null, null, 0, 20)))                       // + country-wide
                .containsExactly("Entry rules", "Kandy", "Monsoon", "Packing for the hills");
        assertThat(titles(service.list(null, null, null, false, true, null, 0, 20))).containsExactly("Entry rules", "Monsoon");
        assertThat(titles(service.list(null, null, null, false, false, null, 0, 20))).containsExactly("Galle", "Kandy", "Packing for the hills");
        assertThat(titles(service.list(null, null, null, false, null, List.of("category,asc", "title,desc"), 0, 20)))
                .containsExactly("Kandy", "Galle", "Packing for the hills", "Entry rules", "Monsoon");
        assertThat(titles(service.list(null, null, null, false, null, List.of("destination,asc", "title,asc"), 0, 20)))
                .contains("Galle", "Kandy");

        PageResponse<InfoArticleSummaryResponse> second = service.list(null, null, null, false, null, null, 1, 2);
        assertThat(second.totalElements()).isEqualTo(5);
        assertThat(second.content()).hasSize(2);
        assertThat(service.list(null, null, null, false, null, null, 2, 2).last()).isTrue();
    }

    @Test
    void searchTreatsPercentAndUnderscoreAsPlainText() {
        article("GENERAL", 1, "Kandy");

        assertThat(service.list("%", null, null, false, null, null, 0, 20).content()).isEmpty();
        assertThat(service.list("_", null, null, false, null, null, 0, 20).content()).isEmpty();
    }

    @Test
    void listRejectsBadCategorySortAndPaging() {
        assertField(() -> service.list(null, "GOSSIP", null, false, null, null, 0, 20), "category");
        assertField(() -> service.list(null, null, null, false, null, List.of("password,asc"), 0, 20), "sort");
        assertThatThrownBy(() -> service.list(null, null, null, false, null, null, 0, 0)).isInstanceOf(InvalidRequestException.class);
    }

    // ------------------------------------------------------------------ helpers

    private InfoArticleResponse article(String category, Integer destinationId, String title) {
        return service.create(body("category", category, "destinationId", destinationId, "title", title, "body", "About " + title + "."));
    }

    private static List<String> titles(PageResponse<InfoArticleSummaryResponse> page) {
        return page.content().stream().map(InfoArticleSummaryResponse::title).toList();
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
