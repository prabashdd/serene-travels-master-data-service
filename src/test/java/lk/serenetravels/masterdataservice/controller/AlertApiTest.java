package lk.serenetravels.masterdataservice.controller;

import lk.serenetravels.masterdataservice.TestDatabase;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The alert endpoints through the real security chain and error mapping. The rules are covered in AlertServiceTest.
@SpringBootTest(properties = {
        "spring.cloud.consul.enabled=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql"
})
class AlertApiTest {

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

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        if (jdbc.sql("select count(*) from vehicle_type").query(Long.class).single() == 0) {
            jdbc.sql("insert into vehicle_type (name, min_capacity, max_capacity) values ('Van', 1, 9)").update();
        }
    }

    @Test
    void agentsCannotSeeAlerts() throws Exception {
        String agent = bearer("agent-user", "AGENT");

        mvc.perform(get(BASE + "/alerts").header(HttpHeaders.AUTHORIZATION, agent)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/alerts/summary").header(HttpHeaders.AUTHORIZATION, agent)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/alerts")).andExpect(status().isUnauthorized());
    }

    @Test
    void aManagerSeesAnExpiredDocumentAsCriticalAndInTheSummary() throws Exception {
        String manager = bearer("boss", "MANAGER");
        String registration = "AL-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        jdbc.sql("""
                        insert into vehicle (registration_no, vehicle_type_id, seating_capacity, method, insurance_expiry_date,
                                             revenue_license_expiry_date)
                        values (:reg, (select min(id) from vehicle_type), 7, 'INTERNAL', :past, :far)""")
                .param("reg", registration).param("past", LocalDate.now().minusDays(3)).param("far", LocalDate.now().plusDays(400))
                .update();

        mvc.perform(get(BASE + "/alerts").param("code", "vehicle_insurance_expiring").param("severity", "critical")
                        .header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.subject.name=='" + registration + "')].severity").value(Matchers.contains("CRITICAL")))
                .andExpect(jsonPath("$.content[?(@.subject.name=='" + registration + "')].daysLeft").value(Matchers.contains(-3)))
                .andExpect(jsonPath("$.content[?(@.subject.name=='" + registration + "')].subjectType").value(Matchers.contains("vehicle")));
        mvc.perform(get(BASE + "/alerts/summary").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(Matchers.greaterThanOrEqualTo(2)))   // insurance + no driver
                .andExpect(jsonPath("$.bySeverity.CRITICAL").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.byCode.VEHICLE_INSURANCE_EXPIRING").value(Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.byCode.RATE_ENDING_SOON").value(0));
    }

    @Test
    void badFiltersComeBackAsProblemJson() throws Exception {
        String manager = bearer("boss", "MANAGER");

        mvc.perform(get(BASE + "/alerts").param("code", "GOSSIP").param("severity", "LOUD").param("withinDays", "-1")
                        .header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.code").exists())
                .andExpect(jsonPath("$.fieldErrors.severity").exists())
                .andExpect(jsonPath("$.fieldErrors.withinDays").exists());
        mvc.perform(get(BASE + "/alerts/summary").param("withinDays", "99999").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.withinDays").exists());
        mvc.perform(get(BASE + "/alerts").param("withinDays", "soon").header(HttpHeaders.AUTHORIZATION, manager))
                .andExpect(status().isBadRequest());
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
