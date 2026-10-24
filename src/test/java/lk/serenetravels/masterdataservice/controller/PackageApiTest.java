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

// The package endpoints through the real security chain, JSON binding and error mapping. Business rules are
// covered in PackageServiceTest; this checks roles, status codes and the shapes the FE sees.
@SpringBootTest(properties = {
        "spring.cloud.consul.enabled=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql"
})
class PackageApiTest {

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
    private int rockId;
    private int templeId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        if (jdbc.sql("select count(*) from destination").query(Long.class).single() == 0) {
            jdbc.sql("insert into destination (name, country) values ('Sigiriya', 'Sri Lanka')").update();
        }
        if (jdbc.sql("select count(*) from attraction where name in ('Pkg Rock', 'Pkg Temple')").query(Long.class).single() == 0) {
            jdbc.sql("""
                    insert into attraction (destination_id, name, pricing_method)
                    select min(id), 'Pkg Rock', 'PER_PERSON' from destination""").update();
            jdbc.sql("""
                    insert into attraction (destination_id, name, pricing_method)
                    select min(id), 'Pkg Temple', 'PER_PERSON' from destination""").update();
        }
        rockId = jdbc.sql("select id from attraction where name = 'Pkg Rock'").query(Integer.class).single();
        templeId = jdbc.sql("select id from attraction where name = 'Pkg Temple'").query(Integer.class).single();
    }

    @Test
    void agentsCannotReadOrChangePackages() throws Exception {
        String agent = bearer("agent-user", "AGENT");

        mvc.perform(get(BASE + "/packages").header(HttpHeaders.AUTHORIZATION, agent)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/packages").header(HttpHeaders.AUTHORIZATION, agent)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(put(BASE + "/packages/1/sites").header(HttpHeaders.AUTHORIZATION, agent)
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isForbidden());
    }

    @Test
    void aManagerCanRunPackagesEndToEnd() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        String created = mvc.perform(post(BASE + "/packages").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Triangle " + suffix + "\",\"attractionIds\":[" + templeId + "," + rockId + "]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, Matchers.containsString("/packages/")))
                .andExpect(jsonPath("$.sites.length()").value(2))
                .andExpect(jsonPath("$.sites[0].name").value("Pkg Rock"))
                .andExpect(jsonPath("$.currentRate").value(Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");

        // the price is set through the generic rate endpoint and shows up on the package
        mvc.perform(put(BASE + "/rates/package").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":{\"packageId\":" + id + "},\"rate\":150}"))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "/packages/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentRate.rate").value(150))
                .andExpect(jsonPath("$.currentRate.unit").value("USD"));
        mvc.perform(get(BASE + "/packages/" + id).param("at", "2000-01-01").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(jsonPath("$.currentRate").value(Matchers.nullValue()));

        mvc.perform(put(BASE + "/packages/" + id + "/sites").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("[" + templeId + "]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Pkg Temple"));

        mvc.perform(patch(BASE + "/packages/" + id).header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Triangle Plus " + suffix + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Triangle Plus " + suffix))
                .andExpect(jsonPath("$.sites.length()").value(1))
                .andExpect(jsonPath("$.currentRate.rate").value(150));

        // filter by site, search by name, sort=name,asc reaches the service whole
        mvc.perform(get(BASE + "/packages").param("q", suffix).param("attractionId", String.valueOf(templeId))
                        .param("sort", "name,asc").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));
        mvc.perform(get(BASE + "/packages").param("q", suffix).param("attractionId", String.valueOf(rockId))
                        .header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(jsonPath("$.totalElements").value(0));

        // a priced package cannot be deleted; an unpriced one can
        mvc.perform(delete(BASE + "/packages/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IN_USE"));
        String spare = mvc.perform(post(BASE + "/packages").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Spare " + suffix + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int spareId = JsonPath.read(spare, "$.id");
        mvc.perform(delete(BASE + "/packages/" + spareId).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNoContent());
        mvc.perform(get(BASE + "/packages/" + spareId).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void packageErrorsComeBackAsProblemJson() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String body = "{\"name\":\"Dup " + UUID.randomUUID().toString().substring(0, 6) + "\"}";

        mvc.perform(post(BASE + "/packages").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists());

        mvc.perform(post(BASE + "/packages").header(HttpHeaders.AUTHORIZATION, manager)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post(BASE + "/packages").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"));

        // a bad site is reported against the offending list item
        mvc.perform(post(BASE + "/packages").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"attractionIds\":[" + rockId + ",999999]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['attractionIds[1]']").exists());
        mvc.perform(put(BASE + "/packages/1/sites").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"not\":\"a list\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get(BASE + "/packages").param("sort", "password,asc").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.sort").exists());
        mvc.perform(get(BASE + "/packages").param("at", "yesterday").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(BASE + "/packages/999999").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
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
