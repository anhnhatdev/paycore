package com.paycore.fraudservice.repository;

import com.paycore.fraudservice.domain.entity.FraudRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FraudRuleRepository extends JpaRepository<FraudRule, UUID> {
    List<FraudRule> findByEnabledTrue();
    Optional<FraudRule> findByRuleCode(String ruleCode);
}
