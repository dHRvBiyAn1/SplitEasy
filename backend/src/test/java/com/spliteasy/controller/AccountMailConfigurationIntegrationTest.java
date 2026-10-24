package com.spliteasy.controller;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.spliteasy.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@SpringBootTest(
    properties = {
      "app.mail.enabled=true",
      "app.mail.from=no-reply@example.test",
      "app.mail.frontend-url=https://app.example.test",
      "app.mail.allow-insecure-local=false"
    })
@Import(AccountMailConfigurationIntegrationTest.MailConfiguration.class)
class AccountMailConfigurationIntegrationTest extends AbstractIntegrationTest {
  @Autowired ControlledSender sender;

  @org.junit.jupiter.api.BeforeEach
  void configureSender() {
    sender.getJavaMailProperties().clear();
    sender.setUsername("owned-test-user");
    sender.setPassword("owned-test-password");
    sender.setHost("smtp.example.test");
    sender.setProtocol("smtp");
    sender.getJavaMailProperties().setProperty("mail.smtp.ssl.checkserveridentity", "true");
    sender.getJavaMailProperties().setProperty("mail.smtp.auth", "true");
    sender.getJavaMailProperties().setProperty("mail.smtp.starttls.enable", "true");
    sender.getJavaMailProperties().setProperty("mail.smtp.starttls.required", "true");
    for (String timeout : new String[] {"connectiontimeout", "timeout", "writetimeout"}) {
      sender.getJavaMailProperties().setProperty("mail.smtp." + timeout, "5000");
    }
  }

  @TestConfiguration
  static class MailConfiguration {
    @Bean
    ControlledSender sender() {
      return new ControlledSender();
    }
  }

  static class ControlledSender extends JavaMailSenderImpl {
    ControlledSender() {
      setHost("smtp.example.test");
      setUsername("owned-test-user");
      setPassword("owned-test-password");
      getJavaMailProperties().setProperty("mail.smtp.auth", "true");
      getJavaMailProperties().setProperty("mail.smtp.starttls.enable", "true");
      getJavaMailProperties().setProperty("mail.smtp.starttls.required", "true");
    }

    @Override
    public void send(SimpleMailMessage message) {}
  }

  @Test
  void remoteSmtpRequiresHostnameVerificationBeforeAnyAccountLookupOrSend() throws Exception {
    sender.getJavaMailProperties().remove("mail.smtp.ssl.checkserveridentity");
    for (String timeout : new String[] {"connectiontimeout", "timeout", "writetimeout"}) {
      sender.getJavaMailProperties().setProperty("mail.smtp." + timeout, "5000");
    }
    register("smtp-config@example.com", "password123", "SMTP owner");
    for (String email :
        new String[] {"smtp-config@example.com", "smtp-config-unknown@example.com"}) {
      mockMvc
          .perform(jsonPost("/api/auth/password-reset-requests", Map.of("email", email)))
          .andExpect(status().isServiceUnavailable())
          .andExpect(
              jsonPath("$.message")
                  .value("Email delivery is currently unavailable. Try again later."));
    }
  }

  @Test
  void remoteSmtpRejectsUnboundedTimeoutsAndAcceptsFullyAuthenticatedTls() throws Exception {
    sender.getJavaMailProperties().setProperty("mail.smtp.ssl.checkserveridentity", "true");
    var auth = register("smtp-timeouts@example.com", "password123", "Timeout owner");
    for (String property : new String[] {"connectiontimeout", "timeout", "writetimeout"}) {
      sender.getJavaMailProperties().setProperty("mail.smtp." + property, "5000");
    }
    try {
      for (String property : new String[] {"connectiontimeout", "timeout", "writetimeout"}) {
        for (String invalid : new String[] {"0", "-1", "not-an-integer"}) {
          sender.getJavaMailProperties().setProperty("mail.smtp." + property, invalid);
          mockMvc
              .perform(
                  jsonPost(
                      "/api/auth/password-reset-requests",
                      Map.of("email", "unknown-" + property + invalid + "@example.com")))
              .andExpect(status().isServiceUnavailable());
        }
        sender.getJavaMailProperties().setProperty("mail.smtp." + property, "5000");
      }
      mockMvc
          .perform(
              jsonPost("/api/me/email-verification-requests", Map.of())
                  .header("Authorization", bearer(auth.accessToken())))
          .andExpect(status().isAccepted());
    } finally {
      sender.getJavaMailProperties().clear();
      sender.getJavaMailProperties().setProperty("mail.smtp.auth", "true");
      sender.getJavaMailProperties().setProperty("mail.smtp.starttls.enable", "true");
      sender.getJavaMailProperties().setProperty("mail.smtp.starttls.required", "true");
    }
  }

  @Test
  void remoteSenderSettingsCannotBypassAuthenticatedTlsValidation() throws Exception {
    for (String invalid :
        new String[] {
          "missing-host",
          "alternate-protocol",
          "disabled-auth",
          "optional-starttls",
          "missing-username",
          "missing-password",
          "alternate-session-protocol"
        }) {
      configureSender();
      switch (invalid) {
        case "missing-host" -> sender.setHost("");
        case "alternate-protocol" -> sender.setProtocol("smtps");
        case "disabled-auth" ->
            sender.getJavaMailProperties().setProperty("mail.smtp.auth", "false");
        case "optional-starttls" ->
            sender.getJavaMailProperties().setProperty("mail.smtp.starttls.required", "false");
        case "missing-username" -> sender.setUsername("");
        case "missing-password" -> sender.setPassword("");
        case "alternate-session-protocol" -> {
          sender.setProtocol(null);
          sender.getJavaMailProperties().setProperty("mail.transport.protocol", "smtps");
        }
      }
      mockMvc
          .perform(
              jsonPost(
                  "/api/auth/password-reset-requests", Map.of("email", invalid + "@example.com")))
          .andExpect(status().isServiceUnavailable());
    }
  }
}
