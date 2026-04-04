package com.paycore.ledgerservice.repository;

import com.paycore.ledgerservice.domain.entity.SystemAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SystemAccountRepository extends JpaRepository<SystemAccount, UUID> {

    Optional<SystemAccount> findByCode(String code);

    boolean existsById(UUID id);
}
