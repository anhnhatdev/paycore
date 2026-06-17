package com.paycore.notificationservice.provider;

import com.paycore.notificationservice.domain.enums.NotificationChannel;
import com.paycore.notificationservice.dto.RenderedMessage;
import com.paycore.notificationservice.exception.NotificationDeliveryException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class EmailNotificationProvider implements NotificationProvider {

    // Testing simulation hooks
    private final AtomicBoolean simulateFailure = new AtomicBoolean(false);
    private final AtomicInteger failuresRemaining = new AtomicInteger(0);

    @Override
    public NotificationChannel getChannel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void send(RenderedMessage message) throws NotificationDeliveryException {
        // Controlled failure simulation for integration tests
        if (simulateFailure.get() || failuresRemaining.getAndDecrement() > 0) {
            log.error("Email delivery failed to {}: Provider connection timeout (Simulated)", message.getRecipientMasked());
            throw new NotificationDeliveryException("Email service temporarily unavailable: Connection timeout");
        }

        // Production simulation / Real dispatch log (Only log masked recipient!)
        log.info("DISPATCH: Email successfully delivered to recipient={}, subject=[{}]",
                message.getRecipientMasked(), message.getSubject());
    }

    public void setSimulateFailure(boolean fail) {
        this.simulateFailure.set(fail);
    }

    public void setFailuresBeforeSuccess(int count) {
        this.failuresRemaining.set(count);
    }
}
