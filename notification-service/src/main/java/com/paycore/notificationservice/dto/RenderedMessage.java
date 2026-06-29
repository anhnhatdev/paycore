package com.paycore.notificationservice.dto;

import com.paycore.notificationservice.domain.enums.NotificationChannel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RenderedMessage {
    private UUID notificationId;
    private UUID userId;
    private NotificationChannel channel;
    private String recipient;
    private String recipientMasked;
    private String subject;
    private String body;
    private String templateCode;
}
