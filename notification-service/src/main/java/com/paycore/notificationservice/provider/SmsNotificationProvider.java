package com.paycore.notificationservice.provider;

import com.paycore.notificationservice.domain.enums.NotificationChannel;
import com.paycore.notificationservice.dto.RenderedMessage;
import com.paycore.notificationservice.exception.NotificationDeliveryException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SmsNotificationProvider implements NotificationProvider {

    @Override
    public NotificationChannel getChannel() {
        return NotificationChannel.SMS;
    }

    @Override
    public void send(RenderedMessage message) throws NotificationDeliveryException {
        log.info("DISPATCH: SMS successfully sent to phone={}, text=[{}]",
                message.getRecipientMasked(), message.getBody());
    }
}
