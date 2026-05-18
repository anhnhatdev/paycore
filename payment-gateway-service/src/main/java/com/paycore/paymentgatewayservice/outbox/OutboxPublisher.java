package com.paycore.paymentgatewayservice.outbox;

import com.paycore.paymentgatewayservice.domain.entity.OutboxEvent;
import com.paycore.paymentgatewayservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${payment.gateway.outbox.batch-size:50}")
    private int batchSize;

    @Value("${payment.gateway.outbox.topic:paycore.gateway-events}")
    private String topic;

    @Scheduled(cron = "${payment.gateway.outbox.cron:*/5 * * * * *}")
    @Transactional
    public void publishOutboxEvents() {
        List<OutboxEvent> unpublished = outboxEventRepository.findUnpublishedEvents(PageRequest.of(0, batchSize));

        if (unpublished.isEmpty()) {
            return;
        }

        log.debug("Found {} unpublished payment gateway outbox events to dispatch", unpublished.size());

        for (OutboxEvent event : unpublished) {
            try {
                String key = event.getAggregateId().toString();
                kafkaTemplate.send(topic, key, event.getPayload());

                event.setPublished(true);
                outboxEventRepository.save(event);

                log.info("Dispatched payment gateway outbox event: id={}, type={}, aggregateId={}",
                        event.getId(), event.getEventType(), key);
            } catch (Exception e) {
                log.error("Failed to publish payment gateway outbox event: id={}, type={}",
                        event.getId(), event.getEventType(), e);
            }
        }
    }
}
