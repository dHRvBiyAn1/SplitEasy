package com.spliteasy.service;

import com.spliteasy.dto.auth.LoginRequest;
import com.spliteasy.dto.auth.RegisterRequest;
import com.spliteasy.entity.User;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.repository.UserRepository;
import com.spliteasy.util.Emails;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final AuthSessionService sessions;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  private final FinancialParticipantService financialParticipants;

  @Transactional
  public AuthSessionService.IssuedSession register(RegisterRequest request, String userAgent) {
    if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new BadRequestException("Password must be at most 72 UTF-8 bytes");
    }
    String email = Emails.normalize(request.email());
    log.debug("Registering new user with email {}", email);
    if (userRepository.existsByEmail(email)) {
      throw new ConflictException("Email is already registered");
    }
    User user =
        new User(email, passwordEncoder.encode(request.password()), request.displayName().trim());
    userRepository.saveAndFlush(user);
    financialParticipants.createPrimary(user);
    log.info("Registered new user {} ({})", user.getId(), email);
    return sessions.create(user, userAgent);
  }

  @Transactional
  public AuthSessionService.IssuedSession login(LoginRequest request, String userAgent) {
    if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new BadCredentialsException("Invalid email or password");
    }
    String email = Emails.normalize(request.email());
    log.debug("Login attempt for {}", email);
    User user =
        userRepository
            .findLockedById(
                jdbc
                    .query(
                        "select id from users where email = ?",
                        (rs, row) -> rs.getObject(1, java.util.UUID.class),
                        email)
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new BadCredentialsException("Invalid email or password")))
            .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
    if (!user.getEmail().equals(email)
        || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      throw new BadCredentialsException("Invalid email or password");
    }
    log.info("User {} logged in", user.getId());
    return sessions.create(user, userAgent);
  }
}
