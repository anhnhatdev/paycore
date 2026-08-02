package com.paycore.auditservice.redactor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PayloadRedactorTest {

    private PayloadRedactor redactor;

    @BeforeEach
    void setUp() {
        redactor = new PayloadRedactor(new ObjectMapper());
    }

    @Test
    @DisplayName("Redacts sensitive fields in flat JSON payload")
    void redactPayload_FlatJson_RedactsSensitiveKeys() {
        String raw = """
                {"userId":"123","cardNumber":"4111222233334444","cvv":"123","password":"secretPassword","amount":"500.00"}
                """;

        String sanitized = redactor.redactPayload(raw, "payment-gateway", "CardPaymentProcessed");

        assertTrue(sanitized.contains("\"cardNumber\":\"[REDACTED]\""));
        assertTrue(sanitized.contains("\"cvv\":\"[REDACTED]\""));
        assertTrue(sanitized.contains("\"password\":\"[REDACTED]\""));
        assertTrue(sanitized.contains("\"amount\":\"500.00\""));
        assertFalse(sanitized.contains("4111222233334444"));
        assertFalse(sanitized.contains("secretPassword"));
    }

    @Test
    @DisplayName("Redacts nested object and array fields recursively")
    void redactPayload_NestedJson_RedactsDeeply() {
        String raw = """
                {
                    "metadata": {
                        "card": {
                            "cardNumber": "4000123456789010",
                            "cvv2": "999"
                        }
                    },
                    "tokens": ["tok_1", "tok_2"]
                }
                """;

        String sanitized = redactor.redactPayload(raw, "account-service", "UserRegistered");

        assertTrue(sanitized.contains("\"cardNumber\":\"[REDACTED]\""));
        assertTrue(sanitized.contains("\"cvv2\":\"[REDACTED]\""));
        assertFalse(sanitized.contains("4000123456789010"));
    }
}
