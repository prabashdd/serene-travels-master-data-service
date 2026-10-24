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

// The promotion endpoints through the real security chain, JSON binding and error mapping. Business rules are
// covered in PromotionServiceTest; this checks roles, status codes and the shapes the FE sees.
@SpringBootTest(properties = {
        "spring.cloud.consul.enabled=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql"
})
class PromotionApiTest {

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
    void agentsCannotReadOrChangePromotions() throws Exception {
        String agent = bearer("agent-user", "AGENT");

        mvc.perform(get(BASE + "/promotions").header(HttpHeaders.AUTHORIZATION, agent)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/promotions").header(HttpHeaders.AUTHORIZATION, agent)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(delete(BASE + "/promotions/1").header(HttpHeaders.AUTHORIZATION, agent)).andExpect(status().isForbidden());
    }

    @Test
    void aManagerCanRunPromotionsEndToEnd() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        String created = mvc.perform(post(BASE + "/promotions").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Early " + suffix + "\",\"type\":\"percentage\",\"validFrom\":\"2020-01-01\","
                                + "\"validTo\":\"2099-01-01\",\"minGroupSize\":4,\"destinationId\":" + destinationId + "}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, Matchers.containsString("/promotions/")))
                .andExpect(jsonPath("$.type").value("PERCENTAGE"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.destination.name").value("Sigiriya"))
                .andExpect(jsonPath("$.season").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.currentValue").value(Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");

        // the value is set through the generic rate endpoint, in percent for this type, and capped at 100
        mvc.perform(put(BASE + "/rates/promotion").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":{\"promotionId\":" + id + "},\"rate\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unit").value("PERCENT"));
        mvc.perform(put(BASE + "/rates/promotion").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":{\"promotionId\":" + id + "},\"rate\":150,\"effectiveFrom\":\"2099-06-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.rate").exists());
        mvc.perform(get(BASE + "/promotions/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentValue.rate").value(10))
                .andExpect(jsonPath("$.currentValue.unit").value("PERCENT"));
        mvc.perform(get(BASE + "/promotions/" + id).param("at", "2100-01-01").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(jsonPath("$.status").value("ENDED"));

        // the type is now locked; a plain edit still works, and null clears a limit
        mvc.perform(patch(BASE + "/promotions/" + id).header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"FIXED_AMOUNT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TYPE_LOCKED"));
        mvc.perform(patch(BASE + "/promotions/" + id).header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Early Bird " + suffix + "\",\"destinationId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Early Bird " + suffix))
                .andExpect(jsonPath("$.destination").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.minGroupSize").value(4));

        mvc.perform(get(BASE + "/promotions").param("q", suffix).param("type", "percentage").param("validOn", "2025-05-05")
                        .param("sort", "validFrom,desc").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));

        // delete deactivates: it is still there, flagged, and can come back
        mvc.perform(delete(BASE + "/promotions/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNoContent());
        mvc.perform(get(BASE + "/promotions/" + id).header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        mvc.perform(get(BASE + "/promotions").param("q", suffix).param("active", "true").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(patch(BASE + "/promotions/" + id).header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void promotionErrorsComeBackAsProblemJson() throws Exception {
        String manager = bearer("boss", "MANAGER");

        mvc.perform(post(BASE + "/promotions").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.type").exists())
                .andExpect(jsonPath("$.fieldErrors.validFrom").exists())
                .andExpect(jsonPath("$.fieldErrors.validTo").exists());
        mvc.perform(post(BASE + "/promotions").header(HttpHeaders.AUTHORIZATION, manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"type\":\"BOGO\",\"validFrom\":\"2026-12-01\",\"validTo\":\"2026-10-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.type").exists())
                .andExpect(jsonPath("$.fieldErrors.validTo").exists());
        mvc.perform(get(BASE + "/promotions").param("type", "BOGO").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.type").exists());
        mvc.perform(get(BASE + "/promotions").param("sort", "password,asc").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.sort").exists());
        mvc.perform(get(BASE + "/promotions").param("validOn", "tomorrow").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/promotions/999999").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(delete(BASE + "/promotions/999999").header(HttpHeaders.AUTHORIZATION, manager))
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
