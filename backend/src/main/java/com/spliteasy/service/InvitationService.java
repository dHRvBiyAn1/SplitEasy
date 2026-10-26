package com.spliteasy.service;

import com.spliteasy.config.ParticipantContract;
import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.dto.invitation.InvitationDtos.*;
import com.spliteasy.entity.User;
import com.spliteasy.exception.*;
import com.spliteasy.repository.*;
import com.spliteasy.util.Emails;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** A pending capability authorizes only the explicit invitation preview/join/claim commands. */
@Service
@RequiredArgsConstructor
public class InvitationService {
  private final JdbcClient jdbc;
  private final GroupWriteGuard writes;
  private final MembershipGuard membership;
  private final GroupRepository groups;
  private final UserRepository users;
  private final FinancialParticipantService financial;
  private final BalanceService balances;
  private final CreateOperations operations;
  private final AccountMailService mail;
  private final SecureRandom random = new SecureRandom();
  private static final String UNAVAILABLE =
      "This invitation is unavailable or expired. Reopen a current invitation or request a new one.";

  private record Invitation(
      UUID id,
      UUID group,
      String kind,
      UUID participant,
      String target,
      String hash,
      Instant created,
      Instant expires,
      Instant revoked,
      Instant accepted,
      UUID acceptedBy,
      String groupName,
      String participantName) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<Status> list(UUID actor, UUID group) {
    membership.requireMember(group, actor);
    ParticipantContract.requireEnabled();
    return find("i.group_id=?", group).stream().map(this::status).toList();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Status email(UUID actor, UUID group, EmailInvitation request) {
    writes.lockForMember(group, actor);
    ParticipantContract.requireEnabled();
    String email = Emails.normalize(request.email());
    var intent =
        new EmailInvitation(
            email, request.participantId(), null, Boolean.TRUE.equals(request.resend()));
    return operations.create(
        actor,
        "invitation",
        group,
        request.operationId(),
        intent,
        Status.class,
        r -> {},
        () -> {
          if (request.participantId() != null) requireUnclaimed(group, request.participantId());
          var candidates =
              request.participantId() == null
                  ? find(
                      "i.group_id=? AND i.kind='EMAIL' AND i.target_email=? AND i.participant_id IS NULL",
                      group,
                      email)
                  : find(
                      "i.group_id=? AND i.kind='EMAIL' AND i.participant_id=?",
                      group,
                      request.participantId());
          var pending = candidates.stream().filter(this::pending).toList();
          if (!pending.isEmpty() && !Boolean.TRUE.equals(request.resend())) {
            if (email.equals(pending.getFirst().target())) return status(pending.getFirst());
            throw new ConflictException(
                "Revoke or explicitly resend the existing invitation before choosing another recipient");
          }
          mail.requireConfigured();
          for (var old : pending)
            jdbc.sql("UPDATE group_invitations SET revoked_at=clock_timestamp() WHERE id=?")
                .param(old.id())
                .update();
          String token = token();
          var invite = insert(actor, group, "EMAIL", request.participantId(), email, token);
          mail.sendInvitation(email, token);
          return status(invite);
        });
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Link link(UUID actor, UUID group) {
    writes.lockForMember(group, actor);
    ParticipantContract.requireEnabled();
    String token = token();
    var invitation = insert(actor, group, "LINK", null, null, token);
    return new Link(status(invitation), AccountMailService.invitationPath(token));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void revoke(UUID actor, UUID group, UUID id) {
    writes.lockForMember(group, actor);
    ParticipantContract.requireEnabled();
    var rows = find("i.group_id=? AND i.id=?", group, id);
    if (rows.isEmpty()) throw new NotFoundException("Invitation not found");
    if (rows.getFirst().accepted() != null)
      throw new ConflictException(
          "An accepted invitation cannot undo membership or participant ownership");
    jdbc.sql(
            "UPDATE group_invitations SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE id=?")
        .param(id)
        .update();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Preview preview(UUID actor, Token request) {
    ParticipantContract.requireEnabled();
    var invite = lookup(request.token());
    User account = users.findById(actor).orElseThrow(this::unavailable);
    validate(invite, account);
    ParticipantSummary participant =
        invite.participant() == null
            ? null
            : requireUnclaimed(invite.group(), invite.participant());
    var nets = balances.snapshot(invite.group()).balances();
    var currencies = new TreeSet<com.spliteasy.entity.LedgerCurrency>();
    nets.forEach(n -> currencies.add(n.currency()));
    var preview =
        currencies.stream()
            .map(
                currency -> {
                  long existing =
                      nets.stream()
                          .filter(n -> n.currency() == currency && n.user().id().equals(actor))
                          .mapToLong(n -> n.netMinor())
                          .reduce(0, Math::addExact);
                  long guest =
                      participant == null
                          ? 0
                          : nets.stream()
                              .filter(
                                  n ->
                                      n.currency() == currency
                                          && n.user().id().equals(participant.id()))
                              .mapToLong(n -> n.netMinor())
                              .reduce(0, Math::addExact);
                  return new ClaimBalance(
                      currency, existing, guest, Math.addExact(existing, guest));
                })
            .toList();
    return new Preview(
        invite.id(),
        invite.kind(),
        invite.group(),
        invite.groupName(),
        invite.expires(),
        participant,
        participant != null,
        preview);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Accepted accept(UUID actor, Token request) {
    ParticipantContract.requireEnabled();
    // Read only candidate group identity; no managed account is loaded before its lock.
    var candidate = lookup(request.token());
    groups.findByIdForUpdate(candidate.group()).orElseThrow(this::unavailable);
    User account = users.findLockedById(actor).orElseThrow(this::unavailable);
    requireCurrentSession(account);
    var invite = lookup(request.token());
    validate(invite, account);
    boolean claim = invite.participant() != null;
    if (claim) {
      requireUnclaimed(invite.group(), invite.participant());
      if (!Boolean.TRUE.equals(request.confirmOwnership()))
        throw new BadRequestException(
            "Review and explicitly confirm ownership of this participant before accepting");
      jdbc.sql(
              "INSERT INTO participant_account_links(participant_id,user_id,linked_at,link_kind,invitation_id) VALUES(?,?,clock_timestamp(),'VERIFIED_EMAIL_CLAIM',?)")
          .params(invite.participant(), actor, invite.id())
          .update();
    }
    jdbc.sql(
            "INSERT INTO group_memberships(id,group_id,user_id,joined_at) VALUES(?,?,?,clock_timestamp()) ON CONFLICT(group_id,user_id) DO NOTHING")
        .params(UUID.randomUUID(), invite.group(), actor)
        .update();
    financial.activate(invite.group(), actor);
    jdbc.sql("UPDATE group_invitations SET accepted_at=clock_timestamp(),accepted_by=? WHERE id=?")
        .params(actor, invite.id())
        .update();
    return new Accepted(invite.group(), claim ? invite.participant() : actor, actor, claim);
  }

  private void requireCurrentSession(User account) {
    var authentication =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    boolean active = false;
    if (authentication
        instanceof
        org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
            authenticated) {
      var jwt = authenticated.getToken();
      Object raw = jwt.getClaims().getOrDefault("credentialVersion", 0L);
      if (raw instanceof Number version
          && version.longValue() == account.getCredentialVersion()
          && jwt.getExpiresAt() != null
          && jwt.getExpiresAt().isAfter(Instant.now())) {
        active =
            jdbc.sql(
                    "SELECT EXISTS(SELECT 1 FROM auth_sessions WHERE id=? AND user_id=? AND credential_version=? AND revoked_at IS NULL AND expires_at>clock_timestamp() AND idle_expires_at>clock_timestamp())")
                .params(
                    UUID.fromString(jwt.getClaimAsString("sid")),
                    account.getId(),
                    account.getCredentialVersion())
                .query(Boolean.class)
                .single();
      }
    }
    if (!active)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.UNAUTHORIZED,
          "Your session changed. Sign in and reopen this invitation.");
  }

  private ParticipantSummary requireUnclaimed(UUID group, UUID id) {
    var person = financial.originals(group).get(id);
    if (person == null || person.accountId() != null) throw unavailable();
    boolean scope =
        jdbc.sql("SELECT EXISTS(SELECT 1 FROM participants WHERE id=? AND scope_group_id=?)")
            .params(id, group)
            .query(Boolean.class)
            .single();
    if (!scope) throw unavailable();
    return person;
  }

  private Invitation insert(
      UUID actor, UUID group, String kind, UUID participant, String email, String token) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO group_invitations(id,group_id,kind,participant_id,target_email,token_hash,created_by,created_at,expires_at) VALUES(?,?,?,?,?,?,?,clock_timestamp(),clock_timestamp()+interval '24 hours')")
        .param(id)
        .param(group)
        .param(kind)
        .param(participant)
        .param(email)
        .param(hash(token))
        .param(actor)
        .update();
    return find("i.id=?", id).getFirst();
  }

  private Invitation lookup(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw unavailable();
    var found = find("i.token_hash=?", hash(token));
    if (found.isEmpty()) throw unavailable();
    return found.getFirst();
  }

  private void validate(Invitation invite, User account) {
    if (!pending(invite)
        || ("EMAIL".equals(invite.kind())
            && (!account.isEmailVerified() || !account.getEmail().equals(invite.target()))))
      throw unavailable();
  }

  private boolean pending(Invitation i) {
    return i.revoked() == null && i.accepted() == null && i.expires().isAfter(Instant.now());
  }

  private Status status(Invitation i) {
    return new Status(
        i.id(),
        i.kind(),
        i.participant(),
        i.participantName(),
        i.accepted() != null
            ? "ACCEPTED"
            : i.revoked() != null
                ? "REVOKED"
                : i.expires().isAfter(Instant.now()) ? "PENDING" : "EXPIRED",
        i.created(),
        i.expires(),
        i.accepted());
  }

  private List<Invitation> find(String condition, Object... params) {
    return jdbc.sql(
            "SELECT i.*,g.name group_name,p.display_name participant_name FROM group_invitations i JOIN groups g ON g.id=i.group_id LEFT JOIN participants p ON p.id=i.participant_id WHERE "
                + condition
                + " ORDER BY i.created_at DESC,i.id")
        .params(params)
        .query(
            (rs, row) ->
                new Invitation(
                    rs.getObject("id", UUID.class),
                    rs.getObject("group_id", UUID.class),
                    rs.getString("kind"),
                    rs.getObject("participant_id", UUID.class),
                    rs.getString("target_email"),
                    rs.getString("token_hash"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("revoked_at") == null
                        ? null
                        : rs.getTimestamp("revoked_at").toInstant(),
                    rs.getTimestamp("accepted_at") == null
                        ? null
                        : rs.getTimestamp("accepted_at").toInstant(),
                    rs.getObject("accepted_by", UUID.class),
                    rs.getString("group_name"),
                    rs.getString("participant_name")))
        .list();
  }

  private String token() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(token.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private BadRequestException unavailable() {
    return new BadRequestException(UNAVAILABLE);
  }
}
