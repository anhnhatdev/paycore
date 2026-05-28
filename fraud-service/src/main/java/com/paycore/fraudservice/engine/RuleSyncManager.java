package com.paycore.fraudservice.engine;

import com.paycore.fraudservice.domain.entity.FraudRule;
import com.paycore.fraudservice.repository.FraudRuleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RuleSyncManager {

    private final FraudRuleRepository fraudRuleRepository;
    private final RuleEngine ruleEngine;

    @PostConstruct
    public void init() {
        reloadRules();
    }

    @Scheduled(cron = "${fraud.cache.rule-refresh-cron:0 */5 * * * *}")
    public void scheduledSync() {
        log.debug("Executing scheduled fraud rule sync");
        reloadRules();
    }

    @KafkaListener(topics = "${fraud.kafka.rule-updated-topic:fraud.rules.updated}", groupId = "fraud-rule-sync-group", autoStartup = "${fraud.kafka.enabled:false}")
    public void onRuleUpdated(String message) {
        log.info("Received Kafka rule update event: {}", message);
        reloadRules();
    }

    public synchronized void reloadRules() {
        try {
            List<FraudRule> rules = fraudRuleRepository.findByEnabledTrue();
            ruleEngine.updateRules(rules);
            log.info("Successfully reloaded {} active fraud rules into memory", rules.size());
        } catch (Exception e) {
            log.error("Failed to reload fraud rules from database", e);
        }
    }
}
