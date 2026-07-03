package com.spliteasy;

import tools.jackson.databind.ObjectMapper;
import com.spliteasy.dto.AuthResponse;
import com.spliteasy.dto.LoginRequest;
import com.spliteasy.dto.RegisterRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base for full-stack tests: real Spring Security filter chain + JWT decoding,
 * backed by a throwaway Postgres via {@link TestcontainersConfiguration}.
 * Subclasses annotate with {@code @AutoConfigureMockMvc}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    protected MockHttpServletRequestBuilder jsonPost(String url, Object body) throws Exception {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(json(body));
    }

    /** Registers a user and returns the issued access token. */
    protected AuthResponse register(String email, String password, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(
                        jsonPost("/api/auth/register", new RegisterRequest(email, password, displayName)))
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
    }

    protected AuthResponse login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(jsonPost("/api/auth/login", new LoginRequest(email, password)))
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }
}
