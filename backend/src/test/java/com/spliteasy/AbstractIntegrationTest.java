package com.spliteasy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.auth.LoginRequest;
import com.spliteasy.dto.auth.RegisterRequest;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * Base for full-stack tests: real Spring Security filter chain + JWT decoding, backed by a
 * throwaway Postgres via {@link TestcontainersConfiguration}. Subclasses annotate with
 * {@code @AutoConfigureMockMvc}.
 */
// Compatibility fixtures remain deterministic after the production rollout deadline.
@SpringBootTest(properties = "app.expenses.version-required-from=2099-01-01T00:00:00Z")
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {
  private static final AtomicInteger CLIENT_SEQUENCE = new AtomicInteger();
  private final int clientId = CLIENT_SEQUENCE.incrementAndGet();
  // Each test owns a client, while repeated attempts within a test share its real policy bucket.
  private final String clientIp = "198.18." + (clientId / 256) + "." + (clientId % 256);

  @Autowired protected MockMvc mockMvc;

  @Autowired protected ObjectMapper objectMapper;

  protected String json(Object value) throws Exception {
    return objectMapper.writeValueAsString(value);
  }

  protected MockHttpServletRequestBuilder jsonPost(String url, Object body) throws Exception {
    return post(url)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json(body))
        .with(
            request -> {
              request.setRemoteAddr(clientIp);
              return request;
            });
  }

  /** Registers a user and returns the issued access token. */
  protected AuthResponse register(String email, String password, String displayName)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                jsonPost("/api/auth/register", new RegisterRequest(email, password, displayName)))
            .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
  }

  protected AuthResponse login(String email, String password) throws Exception {
    MvcResult result =
        mockMvc.perform(jsonPost("/api/auth/login", new LoginRequest(email, password))).andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
  }

  protected String bearer(String token) {
    return "Bearer " + token;
  }
}
