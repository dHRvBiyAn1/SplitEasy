package com.spliteasy.export;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** P07-A read model: the caller's own profile row and the scopes they can currently access. */
@Repository
@RequiredArgsConstructor
public class ExportQuery {
  private final JdbcClient jdbc;

  /** Only what the account owns; never the password hash. */
  public record Profile(
      UUID id,
      String email,
      String displayName,
      String phone,
      String avatarColor,
      String preferredCurrency,
      OffsetDateTime createdAt) {}

  public record Group(
      UUID id,
      String name,
      String type,
      boolean simplifyDebts,
      OffsetDateTime createdAt,
      OffsetDateTime archivedAt) {}

  /** A personal (non-group) ledger, labelled with the other person's display name. */
  public record PersonalLedger(UUID id, String friendName, OffsetDateTime createdAt) {}

  public Profile profile(UUID viewer) {
    return jdbc.sql(
            "SELECT id,email,display_name,phone,avatar_color,preferred_currency,created_at"
                + " FROM users WHERE id=?")
        .param(viewer)
        .query(Profile.class)
        .single();
  }

  public List<Group> groups(UUID viewer) {
    return jdbc.sql(
            "SELECT g.id,g.name,g.type,g.simplify_debts,g.created_at,g.archived_at"
                + " FROM group_memberships gm JOIN groups g ON g.id=gm.group_id"
                + " WHERE gm.user_id=? AND g.deleted_at IS NULL AND g.kind='GROUP'"
                + " ORDER BY g.created_at,g.id")
        .param(viewer)
        .query(Group.class)
        .list();
  }

  public List<PersonalLedger> personalLedgers(UUID viewer) {
    return jdbc.sql(
            "SELECT g.id,u.display_name AS friend_name,pl.created_at"
                + " FROM group_memberships gm JOIN groups g ON g.id=gm.group_id"
                + " JOIN personal_ledgers pl ON pl.group_id=g.id"
                + " JOIN users u ON u.id=CASE WHEN pl.account_low=gm.user_id"
                + " THEN pl.account_high ELSE pl.account_low END"
                + " WHERE gm.user_id=? AND g.deleted_at IS NULL AND g.kind='PERSONAL'"
                + " ORDER BY pl.created_at,g.id")
        .param(viewer)
        .query(PersonalLedger.class)
        .list();
  }
}
