package com.spliteasy.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@AutoConfigureMockMvc
@ActiveProfiles("prod")
@TestPropertySource(
    properties = {
      "APP_JWT_SECRET=production-test-key-at-least-32-bytes-long",
      "APP_CORS_ALLOWED_ORIGINS=https://app.example.com"
    })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductionReadinessIntegrationTest extends AbstractIntegrationTest {

  @Autowired private DataSource dataSource;

  @Test
  void readinessChecksDatabaseWithoutExposingDetailsOrOtherManagementEndpoints() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist());
    mockMvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());

    // A real unavailable pool must fail readiness, unlike the liveness endpoint.
    dataSource.unwrap(HikariDataSource.class).close();
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("DOWN"));
    mockMvc.perform(get("/api/health")).andExpect(status().isOk());
  }
}
