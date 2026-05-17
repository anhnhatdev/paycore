package com.paycore.paymentgatewayservice.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SensitiveDataMaskerTest {

    @Test
    @DisplayName("Masks credit card number in JSON object")
    void mask_JsonWithCardNumber_MaskedSuccessfully() {
        String json = "{\"cardNumber\":\"4111222233334444\",\"cvv\":\"123\",\"amount\":500000}";
        String masked = SensitiveDataMasker.mask(json);

        assertFalse(masked.contains("4111222233334444"));
        assertTrue(masked.contains("4111********4444"));
        assertFalse(masked.contains("\"123\""));
        assertTrue(masked.contains("\"***\""));
    }

    @Test
    @DisplayName("Masks PAN in plain text string")
    void mask_PlainTextWithCard_MaskedSuccessfully() {
        String text = "User paid with card 4111-2222-3333-4444 at terminal";
        String masked = SensitiveDataMasker.maskRawText(text);

        assertFalse(masked.contains("4111-2222-3333-4444"));
        assertTrue(masked.contains("4111********4444"));
    }

    @Test
    @DisplayName("Handles null and empty strings gracefully")
    void mask_NullOrEmpty_ReturnsOriginal() {
        assertNull(SensitiveDataMasker.mask(null));
        assertEquals("", SensitiveDataMasker.mask(""));
    }
}
