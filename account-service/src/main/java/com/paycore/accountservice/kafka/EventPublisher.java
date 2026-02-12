package com.paycore.accountservice.kafka;

import com.paycore.accountservice.event.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Kafka event publisher for account-service domain events.
 * <p>
 * Note on Outbox Pattern: In a full production setup, events should be written to an
 * outbox table within the same transaction as business data, then published by a
 * separate process (Debezium CDC or polling loop). This ensures no event is lost
 * if the service crashes after DB commit but before Kafka send.
 * <p>
 * For account-service (non-transactional account creation with lower criticality),
 * direct publish is acceptable. Transaction-service and Ledger-service MUST use
 * the full Outbox Pattern.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${kafka.topics.account-created}")
    private String accountCreatedTopic;

    @Value("${kafka.topics.account-frozen}")
    private String accountFrozenTopic;

    @Value("${kafka.topics.user-logged-in}")
    private String userLoggedInTopic;

    public void publishAccountCreated(AccountCreatedEvent event) {
        send(accountCreatedTopic, event.getAccountId().toString(), event);
    }

    public void publishAccountFrozen(AccountFrozenEvent event) {
        send(accountFrozenTopic, event.getAccountId().toString(), event);
    }

    public void publishUserLoggedIn(UserLoggedInEvent event) {
        send(userLoggedInTopic, event.getUserId().toString(), event);
    }

    private void send(String topic, String key, Object payload) {
        CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(topic, key, payload);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish event to topic={} key={}: {}", topic, key, ex.getMessage());
                // Do NOT throw — event failure should not roll back the business transaction.
                // Rely on monitoring/alerting to detect persistent Kafka failures.
            } else {
                log.debug("Event published: topic={} key={} partition={} offset={}",
                        topic, key,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
