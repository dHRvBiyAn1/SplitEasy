package com.spliteasy.service;

import com.spliteasy.exception.DeliveryUnavailableException;
import com.spliteasy.exception.MailDeliveryFailedException;
import jakarta.mail.internet.InternetAddress;
import java.net.URI;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AccountMailService {
  private final ObjectProvider<JavaMailSender> senders;
  private final boolean enabled;
  private final boolean allowInsecureLocal;
  private final String from;
  private final String frontendUrl;

  public AccountMailService(
      ObjectProvider<JavaMailSender> senders,
      @Value("${app.mail.enabled:false}") boolean enabled,
      @Value("${app.mail.allow-insecure-local:false}") boolean allowInsecureLocal,
      @Value("${app.mail.from:}") String from,
      @Value("${app.mail.frontend-url:}") String frontendUrl) {
    this.senders = senders;
    this.enabled = enabled;
    this.allowInsecureLocal = allowInsecureLocal;
    this.from = from;
    this.frontendUrl = frontendUrl.replaceAll("/$", "");
  }

  public void requireConfigured() {
    try {
      if (!enabled || !(senders.getIfAvailable() instanceof JavaMailSenderImpl sender))
        throw unavailable();
      String protocol = sender.getProtocol();
      if (protocol == null)
        protocol = sender.getJavaMailProperties().getProperty("mail.transport.protocol", "smtp");
      if (sender.getHost() == null || sender.getHost().isBlank() || !"smtp".equals(protocol))
        throw unavailable();
      InternetAddress address = new InternetAddress(from, true);
      address.validate();
      if (from.contains("\r") || from.contains("\n") || address.getPersonal() != null)
        throw unavailable();
      URI origin = URI.create(frontendUrl);
      if (origin.getHost() == null
          || origin.getUserInfo() != null
          || origin.getQuery() != null
          || origin.getFragment() != null
          || (origin.getPath() != null && !origin.getPath().isEmpty())) throw unavailable();
      boolean local =
          allowInsecureLocal
              && loopback(sender.getHost())
              && loopback(origin.getHost())
              && Set.of("http", "https").contains(origin.getScheme());
      var properties = sender.getJavaMailProperties();
      boolean tls =
          Boolean.parseBoolean(properties.getProperty("mail.smtp.ssl.enable"))
              || (Boolean.parseBoolean(properties.getProperty("mail.smtp.starttls.enable"))
                  && Boolean.parseBoolean(properties.getProperty("mail.smtp.starttls.required")));
      if (!local
          && (!"https".equals(origin.getScheme())
              || !tls
              || !Boolean.parseBoolean(properties.getProperty("mail.smtp.ssl.checkserveridentity"))
              || !Boolean.parseBoolean(properties.getProperty("mail.smtp.auth"))
              || sender.getUsername() == null
              || sender.getUsername().isBlank()
              || sender.getPassword() == null
              || sender.getPassword().isBlank())) throw unavailable();
      if (!local) {
        for (String timeout : new String[] {"connectiontimeout", "timeout", "writetimeout"}) {
          if (Integer.parseInt(properties.getProperty("mail.smtp." + timeout, "0")) < 1)
            throw unavailable();
        }
      }
      sender.getSession().setDebug(false);
    } catch (DeliveryUnavailableException ex) {
      throw ex;
    } catch (Exception ex) {
      throw unavailable();
    }
  }

  public void send(String email, String action, String token) {
    requireConfigured();
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(email);
    message.setSubject("SplitEasy account confirmation");
    message.setText(
        "Use this link within 30 minutes to continue your SplitEasy account request. "
            + "If you did not request it, ignore this email.\n\n"
            + frontendUrl
            + "/account-action#action="
            + action
            + "&token="
            + token);
    try {
      senders.getObject().send(message);
    } catch (RuntimeException ex) {
      log.warn("Account email delivery failed");
      throw new MailDeliveryFailedException();
    }
  }

  public static String invitationPath(String token) {
    return "/invitation#action=group-invitation&token=" + token;
  }

  public void sendInvitation(String email, String token) {
    requireConfigured();
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(email);
    message.setSubject("SplitEasy group invitation");
    message.setText(
        "Review this invitation within 24 hours. Sign in, verify your email, then reopen this original link and explicitly accept. Ownership of an existing participant is a separate irreversible confirmation.\n\n"
            + frontendUrl
            + invitationPath(token));
    try {
      senders.getObject().send(message);
    } catch (RuntimeException ex) {
      log.warn("Invitation email delivery failed");
      throw new MailDeliveryFailedException();
    }
  }

  private boolean loopback(String host) {
    return host != null && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host);
  }

  private DeliveryUnavailableException unavailable() {
    return new DeliveryUnavailableException(
        "Email delivery is currently unavailable. Try again later.");
  }
}
