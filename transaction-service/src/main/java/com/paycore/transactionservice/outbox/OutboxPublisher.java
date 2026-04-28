package com.paycore.transactionservice.outbox;

import com.paycore.transactionservice.domain.entity.OutboxEvent;
import com.paycore.transactionservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Transactional Outbox Publisher for transaction-service.
 * Polling daemon that reads unpublished transaction events and publishes to Kafka.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${transaction.kafka.topics.transaction-events:paycore.transaction-events}")
    private String transactionEventsTopic;

    @Scheduled(fixedDelayString = "${transaction.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishUnpublishedEvents() {
        List<OutboxEvent> unpublished = outboxEventRepository.findUnpublishedEvents(PageRequest.of(0, 50));
        if (unpublished.isEmpty()) {
            return;
        }

        log.debug("Found {} unpublished transaction outbox events to dispatch", unpublished.size());

        for (OutboxEvent event : unpublished) {
            try {
                var future = kafkaTemplate.send(transactionEventsTopic, event.getAggregateId().toString(), event.getPayload());
                if (future != null) {
                    future.whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.debug("Dispatched transaction outbox event {} to topic {}", event.getId(), transactionEventsTopic);
                        } else {
                            log.error("Failed to dispatch transaction outbox event {}: {}", event.getId(), ex.getMessage());
                        }
                    });
                }
                event.setPublished(true);
            } catch (Exception e) {
                log.error("Exception during outbox publishing for transaction event {}: {}", event.getId(), e.getMessage());
            }
        }

        outboxEventRepository.saveAll(unpublished);
    }
}
