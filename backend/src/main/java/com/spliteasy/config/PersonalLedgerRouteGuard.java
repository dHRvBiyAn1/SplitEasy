package com.spliteasy.config;

import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.PersonalLedgerRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Group routes refuse a personal ledger (migration V21) for its own members with the same 404 a
 * deleted group gives. Everyone else passes through to the route's membership check, so a
 * non-member still gets 403 before 404 and never learns that the ledger exists. Personal expense
 * routes reuse the same services under {@code /api/friends}, which this guard does not cover.
 */
@Configuration
@RequiredArgsConstructor
public class PersonalLedgerRouteGuard implements HandlerInterceptor, WebMvcConfigurer {
  // Lazy: the MVC-only test slice scans this class but has no repositories.
  private final ObjectProvider<PersonalLedgerRepository> ledgers;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this).addPathPatterns("/api/groups/**");
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    var variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (!(variables instanceof Map<?, ?> vars && vars.get("groupId") instanceof String raw))
      return true;
    if (!(SecurityContextHolder.getContext().getAuthentication() != null
        && SecurityContextHolder.getContext().getAuthentication().getPrincipal()
            instanceof Jwt jwt)) return true;
    UUID group;
    try {
      group = UUID.fromString(raw);
    } catch (IllegalArgumentException malformed) {
      return true; // the route's own binding reports it
    }
    if (ledgers.getObject().isMemberOfPersonal(group, UUID.fromString(jwt.getSubject())))
      throw new NotFoundException("Group not found");
    return true;
  }
}
