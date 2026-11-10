package com.spliteasy.service;

import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.entity.User;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Financial identity and roster rules; callers own group authorization and transaction. */
@Service
@RequiredArgsConstructor
public class FinancialParticipantService {
  private final JdbcClient jdbc;

  void requireCompatible(UUID group) {
    if (!com.spliteasy.config.ParticipantContract.enabled()
        && jdbc.sql(
                "SELECT EXISTS(SELECT 1 FROM group_participants gp JOIN participants p ON p.id=gp.participant_id WHERE gp.group_id=? AND p.scope_group_id IS NOT NULL)")
            .param(group)
            .query(Boolean.class)
            .single()) com.spliteasy.config.ParticipantContract.requireEnabled();
  }

  void createPrimary(User user) {
    jdbc.sql("INSERT INTO participants(id,display_name,created_at) VALUES(?,?,?)")
        .params(user.getId(), user.getDisplayName(), java.sql.Timestamp.from(user.getCreatedAt()))
        .update();
    jdbc.sql(
            "INSERT INTO participant_account_links(participant_id,user_id,linked_at,link_kind) VALUES(?,?,?,'PRIMARY')")
        .params(user.getId(), user.getId(), java.sql.Timestamp.from(user.getCreatedAt()))
        .update();
  }

  void activate(UUID group, UUID account) {
    jdbc.sql(
            "INSERT INTO group_participants(group_id,participant_id,active,added_at) VALUES(?,?,true,CURRENT_TIMESTAMP) ON CONFLICT(group_id,participant_id) DO UPDATE SET active=true")
        .params(group, account)
        .update();
    jdbc.sql(
            "UPDATE group_participants SET active=true WHERE group_id=? AND participant_id IN (SELECT participant_id FROM participant_account_links WHERE user_id=?)")
        .params(group, account)
        .update();
  }

  void deactivate(UUID group, UUID account) {
    jdbc.sql(
            "UPDATE group_participants SET active=false WHERE group_id=? AND participant_id IN (SELECT participant_id FROM participant_account_links WHERE user_id=?)")
        .params(group, account)
        .update();
  }

  List<UUID> owned(UUID account) {
    return jdbc.sql(
            "SELECT participant_id FROM participant_account_links WHERE user_id=? ORDER BY participant_id")
        .param(account)
        .query(UUID.class)
        .list();
  }

  Map<UUID, ParticipantSummary> originals(UUID group) {
    return jdbc
        .sql(
            """
 SELECT p.id,CASE WHEN p.scope_group_id IS NULL THEN COALESCE(u.display_name,p.display_name) ELSE p.display_name END name,u.email,l.user_id
 FROM group_participants gp JOIN participants p ON p.id=gp.participant_id
 LEFT JOIN participant_account_links l ON l.participant_id=p.id LEFT JOIN users u ON u.id=l.user_id
 WHERE gp.group_id=?
 """)
        .param(group)
        .query(
            (rs, row) -> {
              UUID id = rs.getObject("id", UUID.class);
              UUID owner = rs.getObject("user_id", UUID.class);
              return new ParticipantSummary(
                  id,
                  rs.getString("email"),
                  rs.getString("name"),
                  owner == null ? id : owner,
                  owner);
            })
        .list()
        .stream()
        .collect(Collectors.toMap(ParticipantSummary::id, p -> p));
  }

  Map<UUID, ParticipantSummary> resolve(UUID group, Collection<UUID> ledgerIds) {
    var original = originals(group);
    var result = new HashMap<UUID, ParticipantSummary>();
    var active = active(group);
    for (var p : original.values()) {
      if (!active.contains(p.id())
          && !ledgerIds.contains(p.id())
          && !ledgerIds.contains(p.canonicalParticipantId())) continue;
      UUID key = p.canonicalParticipantId();
      var presentation = original.getOrDefault(key, p);
      result.put(
          key,
          new ParticipantSummary(
              key, presentation.email(), presentation.displayName(), key, p.accountId()));
    }
    if (!original.keySet().containsAll(ledgerIds))
      throw new IllegalStateException("Missing financial participant");
    return result;
  }

  Set<UUID> active(UUID group) {
    return new LinkedHashSet<>(
        jdbc.sql(
                "SELECT participant_id FROM group_participants WHERE group_id=? AND active ORDER BY participant_id")
            .param(group)
            .query(UUID.class)
            .list());
  }

  List<ParticipantSummary> roster(UUID group) {
    var originals = originals(group);
    var seen = new HashSet<UUID>();
    return active(group).stream()
        .map(originals::get)
        .filter(p -> seen.add(p.canonicalParticipantId()))
        .map(p -> originals.getOrDefault(p.canonicalParticipantId(), p))
        .sorted(
            Comparator.comparing(ParticipantSummary::displayName)
                .thenComparing(ParticipantSummary::id))
        .toList();
  }

  List<ParticipantSummary> inactiveGuests(UUID group) {
    return jdbc.sql(
            "SELECT p.id,p.display_name FROM group_participants gp JOIN participants p ON p.id=gp.participant_id LEFT JOIN participant_account_links l ON l.participant_id=p.id WHERE gp.group_id=? AND NOT gp.active AND p.scope_group_id=gp.group_id AND l.participant_id IS NULL ORDER BY p.display_name,p.id")
        .param(group)
        .query(
            (rs, row) -> {
              UUID id = rs.getObject("id", UUID.class);
              return new ParticipantSummary(id, null, rs.getString("display_name"), id, null);
            })
        .list();
  }

  UUID canonical(UUID group, UUID id) {
    var p = originals(group).get(id);
    if (p == null)
      throw new com.spliteasy.exception.BadRequestException("Participant is not in this group");
    return p.canonicalParticipantId();
  }
}
