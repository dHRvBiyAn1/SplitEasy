package com.spliteasy.repository;

import com.spliteasy.entity.GroupMembership;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupMembershipRepository extends JpaRepository<GroupMembership, UUID> {

    boolean existsByGroupIdAndUserId(UUID groupId, UUID userId);

    /** Ids of every member of a group — used to default an expense's participants to all members. */
    @Query("select m.user.id from GroupMembership m where m.group.id = :groupId")
    List<UUID> findUserIdsByGroupId(@Param("groupId") UUID groupId);

    /**
     * All members of a group with their {@link com.spliteasy.entity.User} eagerly
     * fetched in a single query — avoids an N+1 when building the member list DTO.
     */
    @Query("select m from GroupMembership m join fetch m.user where m.group.id = :groupId")
    List<GroupMembership> findByGroupIdFetchUser(@Param("groupId") UUID groupId);

    /**
     * Summary of every group the user belongs to, including the total member count,
     * in one query (no per-group count round-trip). m1 selects the user's groups;
     * m2 counts all members of each of those groups.
     */
    @Query("""
            select g.id as id, g.name as name, g.type as type, count(m2.id) as memberCount
            from GroupMembership m1
            join m1.group g
            join GroupMembership m2 on m2.group = g
            where m1.user.id = :userId
            group by g.id, g.name, g.type, g.createdAt
            order by g.createdAt desc
            """)
    List<GroupSummaryView> findGroupSummariesForUser(@Param("userId") UUID userId);

    /** Interface projection backing {@link #findGroupSummariesForUser(UUID)}. */
    interface GroupSummaryView {
        UUID getId();

        String getName();

        com.spliteasy.entity.GroupType getType();

        long getMemberCount();
    }
}
