package com.spliteasy.repository;

import com.spliteasy.entity.Group;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupRepository extends JpaRepository<Group, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select g from Group g where g.id = :groupId")
  Optional<Group> findByIdForUpdate(@Param("groupId") UUID groupId);
}
