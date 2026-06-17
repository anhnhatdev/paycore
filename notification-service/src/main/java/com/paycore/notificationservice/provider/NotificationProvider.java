package com.paycore.notificationservice.provider;

import com.paycore.notificationservice.domain.enums.NotificationChannel;
import com.paycore.notificationservice.dto.RenderedMessage;
import com.paycore.notificationservice.exception.NotificationDeliveryException;

public interface NotificationProvider {

    NotificationChannel getChannel();

    void send(RenderedMessage message) throws NotificationDeliveryException;
}
