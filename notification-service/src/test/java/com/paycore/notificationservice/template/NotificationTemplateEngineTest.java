package com.paycore.notificationservice.template;

import com.paycore.notificationservice.domain.enums.NotificationChannel;
import com.paycore.notificationservice.dto.RenderedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NotificationTemplateEngineTest {

    private NotificationTemplateEngine templateEngine;

    @BeforeEach
    void setUp() {
        templateEngine = new NotificationTemplateEngine();
    }

    @Test
    @DisplayName("TransactionCompleted renders proper message with amount and currency")
    void render_TransactionCompleted_Success() {
        Map<String, Object> payload = Map.of(
                "transactionId", UUID.randomUUID().toString(),
                "amount", "500000.00",
                "currency", "VND"
        );

        RenderedMessage message = templateEngine.render(
                "TransactionCompleted",
                NotificationChannel.EMAIL,
                "user@example.com",
                "u**r@example.com",
                payload
        );

        assertEquals("TRANSACTIONCOMPLETED_EMAIL", message.getTemplateCode());
        assertTrue(message.getSubject().contains("thành công"));
        assertTrue(message.getBody().contains("500000.00 VND"));
    }

    @Test
    @DisplayName("AccountFrozen renders high-urgency security message")
    void render_AccountFrozen_SecurityAlert() {
        Map<String, Object> payload = Map.of(
                "reason", "Phát hiện gian lận"
        );

        RenderedMessage message = templateEngine.render(
                "AccountFrozen",
                NotificationChannel.EMAIL,
                "target@example.com",
                "t****t@example.com",
                payload
        );

        assertTrue(message.getSubject().contains("BẢO MẬT KHẨN CẤP"));
        assertTrue(message.getBody().contains("Phát hiện gian lận"));
    }
}
