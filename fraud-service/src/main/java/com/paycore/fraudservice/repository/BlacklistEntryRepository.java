package com.paycore.fraudservice.repository;

import com.paycore.fraudservice.domain.entity.BlacklistEntry;
import com.paycore.fraudservice.domain.enums.EntityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BlacklistEntryRepository extends JpaRepository<BlacklistEntry, UUID> {
    List<BlacklistEntry> findByActiveTrue();
    Optional<BlacklistEntry> findByEntityTypeAndEntityValueAndActiveTrue(EntityType entityType, String entityValue);
    Optional<BlacklistEntry> findByEntityTypeAndEntityValue(EntityType entityType, String entityValue);
}
