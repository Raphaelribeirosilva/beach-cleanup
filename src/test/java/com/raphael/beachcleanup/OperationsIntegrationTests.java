package com.raphael.beachcleanup;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:operationstests", "spring.jpa.show-sql=false"})
@Import(OperationsIntegrationTests.DatabaseHealth.class)
class OperationsIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired MutableDatabaseHealth database;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        database.up = true;
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void probesArePublicAndOnlyExposeStatus() throws Exception {
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            String body = mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP")).andReturn().getResponse().getContentAsString();
            var json = mapper.readTree(body);
            assertFalse(json.has("components"));
            assertFalse(json.has("details"));
            assertFalse(body.contains("confidential-test-detail"));
        }
    }

    @Test
    void databaseFailureStopsReadinessWithoutStoppingLiveness() throws Exception {
        database.up = false;
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("DOWN")).andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void otherManagementRoutesAndWritesAreBlockedEvenForAdministrators() throws Exception {
        for (String path : new String[] {"/actuator", "/actuator/env", "/actuator/configprops", "/actuator/metrics"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/actuator/health").with(user("admin").roles("ADMIN")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/users")).andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class DatabaseHealth {
        @Bean(name = "dbHealthIndicator") @Primary
        MutableDatabaseHealth databaseHealth() { return new MutableDatabaseHealth(); }
    }

    static class MutableDatabaseHealth implements HealthIndicator {
        volatile boolean up = true;
        public Health health() {
            return (up ? Health.up() : Health.down()).withDetail("internal", "confidential-test-detail").build();
        }
    }
}
