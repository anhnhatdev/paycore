package com.paycore.auditservice.repository;

import com.paycore.auditservice.domain.entity.HashCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface HashCheckpointRepository extends JpaRepository<HashCheckpoint, UUID> {

    Optional<HashCheckpoint> findTopByOrderByUpToSequenceNumberDesc();
}
