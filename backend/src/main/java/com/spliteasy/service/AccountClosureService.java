package com.spliteasy.service;

import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Irreversible account closure: blocked while any balance is open, then memberships end, the
 * account row is scrubbed (freeing the email) and stored history is anonymised. Financial rows,
 * amounts and participant IDs are never changed or deleted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountClosureService {
  static final String CLOSED_NAME = "Closed account";

  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final BalanceService balances;
  private final GroupWriteGuard writeGuard;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void close(UUID userId, String currentPassword) {
    var user =
        users
            .findLockedById(userId)
            .filter(u -> u.getClosedAt() == null)
            .orElseThrow(() -> new BadCredentialsException("Account unavailable"));
    if (currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
        || !passwords.matches(currentPassword, user.getPasswordHash()))
      throw new BadRequestException("Current password is incorrect");

    // Lock every group in ascending id order (as B02 does) so an in-flight financial write commits
    // before the balance check and later writers fail their post-lock membership check.
    List<UUID> groups =
        jdbc.queryForList(
            "select g.id from groups g join group_memberships m on m.group_id=g.id where m.user_id=? and g.deleted_at is null order by g.id",
            UUID.class,
            userId);
    groups.forEach(g -> writeGuard.lockForMemberAllowingArchived(g, userId));
    requireSettled(userId, groups);

    String oldEmail = user.getEmail();
    String tombstone = "closed+" + userId + "@closed.invalid";
    for (UUID group : groups) {
      List<UUID> heirs =
          jdbc.queryForList(
              "select user_id from group_memberships where group_id=? and user_id<>? order by joined_at, user_id limit 1",
              UUID.class,
              group,
              userId);
      if (heirs.isEmpty())
        jdbc.update(
            "update groups set deleted_at=CURRENT_TIMESTAMP, lifecycle_version=lifecycle_version+1 where id=?",
            group);
      else
        jdbc.update(
            "update groups set created_by=? where id=? and created_by=?",
            heirs.getFirst(),
            group,
            userId);
    }
    jdbc.update("delete from group_memberships where user_id=?", userId);
    jdbc.update(
        "update group_participants set active=false where participant_id in (select participant_id from participant_account_links where user_id=?)",
        userId);
    jdbc.update(
        "update participants set display_name=? where id in (select participant_id from participant_account_links where user_id=?)",
        CLOSED_NAME,
        userId);
    jdbc.update(
        "update group_invitations set revoked_at=CURRENT_TIMESTAMP where created_by=? and accepted_at is null and revoked_at is null",
        userId);
    jdbc.update(
        "update group_invitations set target_email=? where lower(target_email)=?",
        tombstone,
        oldEmail);
    // Others' bookmarks stay (their private data); writes with a closed account are refused.
    jdbc.update("delete from friends where owner_id=?", userId);
    jdbc.update(
        "update friends set display_name_snapshot=? where friend_account_id=?",
        CLOSED_NAME,
        userId);

    user.setDisplayName(CLOSED_NAME);
    user.setEmail(tombstone);
    user.setEmailVerified(false);
    user.setPhone("");
    user.setPasswordHash("!closed");
    user.setClosedAt(java.time.Instant.now());
    user.setCredentialVersion(Math.incrementExact(user.getCredentialVersion()));
    jdbc.update(
        "update auth_sessions set revoked_at=CURRENT_TIMESTAMP where user_id=? and revoked_at is null",
        userId);
    jdbc.update("delete from account_challenges where user_id=?", userId);
    users.flush();
    anonymise(userId, oldEmail, tombstone);
    log.info("Account {} closed", userId);
  }

  private void requireSettled(UUID userId, List<UUID> groups) {
    List<String> open = new ArrayList<>();
    for (UUID group : groups) {
      boolean unsettled =
          balances.computeBalances(userId, group).balances().stream()
              .anyMatch(b -> userId.equals(b.user().accountId()) && b.netMinor() != 0);
      if (unsettled) open.add(label(userId, group));
    }
    if (!open.isEmpty())
      throw new ConflictException(
          "Settle your balances before closing your account: " + String.join(", ", open) + ".");
  }

  private String label(UUID userId, UUID group) {
    return jdbc.queryForObject(
        "select case when g.kind='PERSONAL' then 'Personal expenses with ' || coalesce((select u.display_name from group_memberships m join users u on u.id=m.user_id where m.group_id=g.id and m.user_id<>? limit 1),'a friend') else g.name end from groups g where g.id=?",
        String.class,
        userId,
        group);
  }

  /** Rewrites stored actor/participant objects and old-email strings; amounts and IDs stay. */
  private void anonymise(UUID userId, String oldEmail, String tombstone) {
    String id = userId.toString();
    scrub(
        "expense_revisions", "actor,before_snapshot,after_snapshot", true, id, oldEmail, tombstone);
    scrub(
        "ledger_revisions", "actor,before_snapshot,after_snapshot", true, id, oldEmail, tombstone);
    scrub("activity_events", "actor,summary", true, id, oldEmail, tombstone);
    scrub("create_operations", "intent,response", false, id, oldEmail, tombstone);
  }

  private void scrub(
      String table, String columns, boolean jsonb, String id, String oldEmail, String tombstone) {
    String[] cols = columns.split(",");
    String cast = jsonb ? "?::jsonb" : "?";
    String match = String.join("::text ilike ? or ", cols) + "::text ilike ?";
    Object[] args = new Object[cols.length];
    java.util.Arrays.fill(args, "%" + id + "%");
    // ponytail: closure is rare, so one pass over matching rows beats a bespoke index.
    var rows =
        jdbc.queryForList(
            "select ctid::text as row_id, " + columns + " from " + table + " where " + match, args);
    for (var row : rows) {
      StringBuilder set = new StringBuilder();
      List<Object> params = new ArrayList<>();
      for (String c : cols) {
        Object value = row.get(c);
        if (value == null) continue;
        if (!set.isEmpty()) set.append(',');
        set.append(c).append('=').append(cast);
        params.add(rewrite(value.toString(), id, oldEmail, tombstone));
      }
      params.add(row.get("row_id"));
      jdbc.update("update " + table + " set " + set + " where ctid=?::tid", params.toArray());
    }
  }

  private String rewrite(String json, String id, String oldEmail, String tombstone) {
    JsonNode tree = mapper.readTree(json);
    return mapper.writeValueAsString(walk(tree, id, oldEmail, tombstone));
  }

  private JsonNode walk(JsonNode node, String id, String oldEmail, String tombstone) {
    if (node.isString())
      return node.asString().equalsIgnoreCase(oldEmail)
          ? mapper.getNodeFactory().stringNode(tombstone)
          : node;
    if (node instanceof ArrayNode array) {
      for (int i = 0; i < array.size(); i++)
        array.set(i, walk(array.get(i), id, oldEmail, tombstone));
    } else if (node instanceof ObjectNode object) {
      boolean mine =
          java.util.stream.Stream.of("id", "accountId", "canonicalParticipantId", "userId")
              .anyMatch(k -> object.has(k) && id.equals(object.get(k).asString()));
      for (String name : List.copyOf(object.propertyNames())) {
        object.set(name, walk(object.get(name), id, oldEmail, tombstone));
      }
      if (mine) {
        if (object.has("displayName")) object.put("displayName", CLOSED_NAME);
        if (object.has("email")) object.put("email", tombstone);
      }
    }
    return node;
  }
}
