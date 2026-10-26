package com.spliteasy.repository;

import com.spliteasy.entity.Participant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipantRepository extends JpaRepository<Participant, UUID> {}
