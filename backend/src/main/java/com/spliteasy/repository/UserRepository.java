package com.spliteasy.repository;

import com.spliteasy.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query("select u from User u where u.id = :id")
  Optional<User> findLockedById(UUID id);

  @org.springframework.data.jpa.repository.Query(
      "select u.credentialVersion from User u where u.id = :id")
  Optional<Long> findCredentialVersion(UUID id);

  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);
}
