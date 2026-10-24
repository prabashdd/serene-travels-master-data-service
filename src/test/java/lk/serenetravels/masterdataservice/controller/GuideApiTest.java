package lk.serenetravels.masterdataservice.controller;

import com.jayway.jsonpath.JsonPath;
import lk.serenetravels.masterdataservice.TestDatabase;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The guide endpoints through the real security chain, JSON binding and error mapping. Business rules are
// covered in GuideServiceTest; this checks roles, status codes and the shapes the FE sees.
@SpringBootTest(properties = {
        "spring.cloud.consul.enabled=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql"
})
class GuideApiTest {

    private static final String BASE = "/api/v1/master-data";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::newUrl);
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private JdbcClient jdbc;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private MockMvc mvc;
    private int englishId;
    private int germanId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        if (jdbc.sql("select count(*) from language").query(Long.class).single() == 0) {
            jdbc.sql("insert into language (name) values ('English'), ('German')").update();
        }
        englishId = jdbc.sql("select id from language where name = 'English'").query(Integer.class).single();
        germanId = jdbc.sql("select id from language where name = 'German'").query(Integer.class).single();
    }

    @Test
    void agentsCannotReadOrChangeGuides() throws Exception {
        String agent = bearer("agent-user", "AGENT");

        mvc.perform(get(BASE + "/guides").header(HttpHeaders.AUTHORIZATION, agent)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/guides").header(HttpHeaders.AUTHORIZATION, agent)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(put(BASE + "/guides/1/languages").header(HttpHeaders.AUTHORIZATION, agent)
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isForbidden());
    }

    @Test
    void aManagerCanRunTheGuideRosterEndToEnd() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        String created = mvc.perform(post(BASE + "/guides").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guideNo\":\"n-" + suffix + "\",\"name\":\"Athula " + suffix + "\",\"guideType\":\"national_guide\","
                                + "\"town\":\"Dehiwela\",\"languageIds\":[" + englishId + "]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, Matchers.containsString("/guides/")))
                .andExpect(jsonPath("$.guideNo").value("N-" + suffix.toUpperCase()))
                .andExpect(jsonPath("$.guideType").value("NATIONAL_GUIDE"))
                .andExpect(jsonPath("$.languages.length()").value(1))
                .andExpect(jsonPath("$.languages[0].name").value("English"))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");

        // add a second language: the PUT carries the complete list
        mvc.perform(put(BASE + "/guides/" + id + "/languages").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("[" + englishId + "," + germanId + "]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].name").value("German"));

        mvc.perform(patch(BASE + "/guides/" + id).header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"town\":null,\"contact\":\"0777490642\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.town").doesNotExist())
                .andExpect(jsonPath("$.contact").value("0777490642"))
                .andExpect(jsonPath("$.languages.length()").value(2));

        // filter by language and search; sort=name,asc must reach the service whole
        mvc.perform(get(BASE + "/guides").param("q", suffix).param("languageId", String.valueOf(germanId))
                        .param("sort", "name,asc").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].languages.length()").value(2));

        mvc.perform(delete(BASE + "/guides/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNoContent());
        mvc.perform(get(BASE + "/guides/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void guideErrorsComeBackAsProblemJson() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String body = "{\"guideNo\":\"d-" + suffix + "\",\"name\":\"Dup\",\"guideType\":\"NATIONAL_GUIDE\"}";

        mvc.perform(post(BASE + "/guides").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.guideNo").exists())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.guideType").exists());

        mvc.perform(post(BASE + "/guides").header(HttpHeaders.AUTHORIZATION, manager)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post(BASE + "/guides").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"));

        // an unknown language id is reported against the offending list item
        mvc.perform(post(BASE + "/guides").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guideNo\":\"x-" + suffix + "\",\"name\":\"X\",\"guideType\":\"NATIONAL_GUIDE\",\"languageIds\":["
                                + englishId + ",98765]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['languageIds[1]']").exists());

        mvc.perform(put(BASE + "/guides/1/languages").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"not\":\"a list\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get(BASE + "/guides").param("sort", "password,asc").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.sort").exists());
        mvc.perform(get(BASE + "/guides").param("guideType", "ASTRONAUT").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.guideType").exists());
        mvc.perform(get(BASE + "/guides/987654").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------ helpers

    // Registers a decodable token for `username` carrying `roles` in realm_access.roles, the claim
    // Keycloak uses, and returns the Authorization header value.
    private String bearer(String username, String... roles) {
        String token = "token-" + username + "-" + String.join("-", roles);
        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject(username + "-sub")
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode(token)).thenReturn(jwt);
        return "Bearer " + token;
    }
}
