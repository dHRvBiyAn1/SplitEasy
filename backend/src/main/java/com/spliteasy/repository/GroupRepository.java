package com.spliteasy.repository;

import com.spliteasy.entity.Group;
import jakarta.persistence.LockModeType;
import java.util.List;
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

  /** Backs the guard's 404 for a recoverably deleted group (migration V19). */
  boolean existsByIdAndDeletedAtIsNotNull(UUID id);

  /** The creator's own recovery list; never exposes another member's deleted groups. */
  @Query(
      "select g from Group g join fetch g.createdBy"
          + " where g.createdBy.id = :creatorId and g.deletedAt is not null"
          + " order by g.deletedAt desc, g.id")
  List<Group> findDeletedByCreator(@Param("creatorId") UUID creatorId);
}
