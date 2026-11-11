package com.spliteasy.repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Pair-scoped hidden ledgers (migration V21); the pair key is the ordered account UUIDs. */
@Repository
@RequiredArgsConstructor
public class PersonalLedgerRepository {
  private final JdbcClient jdbc;

  public Optional<UUID> find(UUID a, UUID b) {
    return jdbc.sql("SELECT group_id FROM personal_ledgers WHERE account_low=? AND account_high=?")
        .params(low(a, b), high(a, b))
        .query(UUID.class)
        .optional();
  }

  /** Native conflict arbitration: false when a concurrent writer already owns the pair. */
  public boolean claim(UUID a, UUID b, UUID group) {
    return jdbc.sql(
                "INSERT INTO personal_ledgers(account_low,account_high,group_id) VALUES(?,?,?) ON CONFLICT DO NOTHING")
            .params(low(a, b), high(a, b), group)
            .update()
        == 1;
  }

  public Set<UUID> ledgersOf(UUID account) {
    return Set.copyOf(
        jdbc.sql("SELECT group_id FROM personal_ledgers WHERE account_low=? OR account_high=?")
            .params(account, account)
            .query(UUID.class)
            .list());
  }

  public boolean isMemberOfPersonal(UUID group, UUID account) {
    return jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM personal_ledgers WHERE group_id=? AND (account_low=? OR account_high=?))")
        .params(group, account, account)
        .query(Boolean.class)
        .single();
  }

  /** An unclaimed guest the account can already see through one of its groups. */
  public boolean isVisibleGuest(UUID participant, UUID account) {
    return jdbc.sql(
            """
            SELECT EXISTS(SELECT 1 FROM group_participants gp
              JOIN group_memberships m ON m.group_id=gp.group_id AND m.user_id=?
              WHERE gp.participant_id=?
                AND NOT EXISTS(SELECT 1 FROM participant_account_links l WHERE l.participant_id=gp.participant_id))
            """)
        .params(account, participant)
        .query(Boolean.class)
        .single();
  }

  // PostgreSQL orders uuid bytes unsigned, unlike UUID.compareTo; lowercase hex text matches it.
  private static boolean ordered(UUID a, UUID b) {
    return a.toString().compareTo(b.toString()) < 0;
  }

  private static UUID low(UUID a, UUID b) {
    return ordered(a, b) ? a : b;
  }

  private static UUID high(UUID a, UUID b) {
    return ordered(a, b) ? b : a;
  }
}
