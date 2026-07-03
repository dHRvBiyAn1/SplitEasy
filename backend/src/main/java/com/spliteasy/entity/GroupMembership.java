package com.spliteasy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(
        name = "group_memberships",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_group_memberships_group_user",
                columnNames = {"group_id", "user_id"}))
public class GroupMembership {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false, updatable = false)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected GroupMembership() {
        // JPA
    }

    public GroupMembership(Group group, User user) {
        this.group = group;
        this.user = user;
        this.joinedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Group getGroup() {
        return group;
    }

    public User getUser() {
        return user;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}
