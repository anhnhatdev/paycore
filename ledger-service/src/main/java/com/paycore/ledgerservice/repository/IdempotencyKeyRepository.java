package com.paycore.ledgerservice.repository;

import com.paycore.ledgerservice.domain.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    List<IdempotencyKey> findByExpiresAtBefore(Instant now);
}
