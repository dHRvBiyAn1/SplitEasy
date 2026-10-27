package com.spliteasy.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FriendRepository {
  private final JdbcClient jdbc;

  public record SavedFriend(UUID friendAccountId, String displayNameSnapshot, Instant createdAt) {}

  public List<SavedFriend> findByOwner(UUID owner) {
    return jdbc.sql(
            "SELECT friend_account_id,display_name_snapshot,created_at FROM friends WHERE owner_id=? ORDER BY display_name_snapshot,created_at,friend_account_id")
        .param(owner)
        .query(SavedFriend.class)
        .list();
  }

  public java.util.Optional<SavedFriend> find(UUID owner, UUID friend) {
    return jdbc.sql(
            "SELECT friend_account_id,display_name_snapshot,created_at FROM friends WHERE owner_id=? AND friend_account_id=?")
        .params(owner, friend)
        .query(SavedFriend.class)
        .optional();
  }

  public void remove(UUID owner, UUID friend) {
    jdbc.sql("DELETE FROM friends WHERE owner_id=? AND friend_account_id=?")
        .params(owner, friend)
        .update();
  }

  public void save(UUID owner, UUID friend, String label) {
    jdbc.sql(
            "INSERT INTO friends(owner_id,friend_account_id,display_name_snapshot) VALUES(?,?,?) ON CONFLICT(owner_id,friend_account_id) DO NOTHING")
        .params(owner, friend, label)
        .update();
  }
}
