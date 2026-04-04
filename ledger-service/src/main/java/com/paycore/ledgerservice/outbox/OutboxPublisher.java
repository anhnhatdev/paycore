package com.paycore.ledgerservice.outbox;

import com.paycore.ledgerservice.domain.entity.OutboxEvent;
import com.paycore.ledgerservice.repository.OutboxEventRepository;
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
 * Transactional Outbox Publisher.
 * Periodically polls unpublished ledger outbox events and dispatches them to Kafka.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${ledger.kafka.topics.ledger-events:paycore.ledger-events}")
    private String ledgerEventsTopic;

    @Scheduled(fixedDelayString = "${ledger.outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishUnpublishedEvents() {
        List<OutboxEvent> unpublished = outboxEventRepository.findUnpublishedEvents(PageRequest.of(0, 50));
        if (unpublished.isEmpty()) {
            return;
        }

        log.debug("Found {} unpublished outbox events to dispatch", unpublished.size());

        for (OutboxEvent event : unpublished) {
            try {
                var future = kafkaTemplate.send(ledgerEventsTopic, event.getAggregateId().toString(), event.getPayload());
                if (future != null) {
                    future.whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.debug("Dispatched outbox event {} to topic {}", event.getId(), ledgerEventsTopic);
                        } else {
                            log.error("Failed to dispatch outbox event {}: {}", event.getId(), ex.getMessage());
                        }
                    });
                }
                event.setPublished(true);
            } catch (Exception e) {
                log.error("Exception during outbox publishing for event {}: {}", event.getId(), e.getMessage());
            }
        }

        outboxEventRepository.saveAll(unpublished);
    }
}
