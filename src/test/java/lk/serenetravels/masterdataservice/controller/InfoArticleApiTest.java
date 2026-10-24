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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The article endpoints through the real security chain, JSON binding and error mapping. Business rules are
// covered in InfoArticleServiceTest; this checks roles, status codes and the shapes the FE sees.
@SpringBootTest(properties = {
        "spring.cloud.consul.enabled=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql"
})
class InfoArticleApiTest {

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
    private int destinationId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        if (jdbc.sql("select count(*) from destination").query(Long.class).single() == 0) {
            jdbc.sql("insert into destination (name, country) values ('Sigiriya', 'Sri Lanka')").update();
        }
        destinationId = jdbc.sql("select min(id) from destination").query(Integer.class).single();
    }

    @Test
    void agentsCannotReadOrChangeArticles() throws Exception {
        String agent = bearer("agent-user", "AGENT");

        mvc.perform(get(BASE + "/info-articles").header(HttpHeaders.AUTHORIZATION, agent)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/info-articles").header(HttpHeaders.AUTHORIZATION, agent)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(delete(BASE + "/info-articles/1").header(HttpHeaders.AUTHORIZATION, agent)).andExpect(status().isForbidden());
    }

    @Test
    void aManagerCanRunArticlesEndToEnd() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        String created = mvc.perform(post(BASE + "/info-articles").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"weather\",\"destinationId\":" + destinationId + ",\"title\":\"Climate " + suffix
                                + "\",\"body\":\"SIGIRIYA\\nHot and dry from May to September.\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, Matchers.containsString("/info-articles/")))
                .andExpect(jsonPath("$.category").value("WEATHER"))
                .andExpect(jsonPath("$.destination.name").value("Sigiriya"))
                .andExpect(jsonPath("$.body").value("SIGIRIYA\nHot and dry from May to September."))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");

        // the list shows an excerpt, never the body
        mvc.perform(get(BASE + "/info-articles").param("q", suffix).param("category", "weather")
                        .param("sort", "title,asc").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id))
                .andExpect(jsonPath("$.content[0].excerpt").value("Hot and dry from May to September."))
                .andExpect(jsonPath("$.content[0].bodyLength").value(43))
                .andExpect(jsonPath("$.content[0].body").doesNotExist());

        mvc.perform(patch(BASE + "/info-articles/" + id).header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"destinationId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destination").value(Matchers.nullValue()));
        mvc.perform(get(BASE + "/info-articles").param("q", suffix).param("countryWide", "true")
                        .header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(BASE + "/info-articles").param("q", suffix).param("destinationId", String.valueOf(destinationId))
                        .param("includeCountryWide", "true").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(delete(BASE + "/info-articles/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNoContent());
        mvc.perform(get(BASE + "/info-articles/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void articleErrorsComeBackAsProblemJson() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String body = "{\"category\":\"VISA\",\"title\":\"Entry " + UUID.randomUUID().toString().substring(0, 6)
                + "\",\"body\":\"Apply online.\"}";

        mvc.perform(post(BASE + "/info-articles").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.category").exists())
                .andExpect(jsonPath("$.fieldErrors.title").exists())
                .andExpect(jsonPath("$.fieldErrors.body").exists());
        mvc.perform(post(BASE + "/info-articles").header(HttpHeaders.AUTHORIZATION, manager)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post(BASE + "/info-articles").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"));
        mvc.perform(get(BASE + "/info-articles").param("category", "GOSSIP").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.category").exists());
        mvc.perform(get(BASE + "/info-articles").param("sort", "password,asc").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.sort").exists());
        mvc.perform(patch(BASE + "/info-articles/999999").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(BASE + "/info-articles/999999").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNotFound());
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
