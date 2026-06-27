package com.paycore.notificationservice.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecipientMaskerTest {

    @Test
    @DisplayName("Email masking preserves first/last character of local-part and full domain")
    void maskEmail_StandardEmail_CorrectlyMasked() {
        // "john.doe" = 8 chars → j + 6 asterisks + e
        assertEquals("j******e@example.com", RecipientMasker.maskEmail("john.doe@example.com"));
        // "user" = 4 chars → u + 2 asterisks + r
        assertEquals("u**r@gmail.com", RecipientMasker.maskEmail("user@gmail.com"));
        // "ab" = 2 chars → a + 1 asterisk (length 2 falls into <= 2 branch)
        assertEquals("a*@domain.com", RecipientMasker.maskEmail("ab@domain.com"));
    }

    @Test
    @DisplayName("Phone number masking masks middle digits")
    void maskPhone_StandardPhone_CorrectlyMasked() {
        assertEquals("090***4567", RecipientMasker.maskPhone("0901234567"));
        assertEquals("+849***4567", RecipientMasker.maskPhone("+84901234567"));
    }

    @Test
    @DisplayName("Null or empty values are handled safely without throwing")
    void mask_NullOrEmpty_HandledSafely() {
        assertEquals("***", RecipientMasker.maskEmail(null));
        assertEquals("***", RecipientMasker.maskEmail(""));
        assertEquals("***", RecipientMasker.maskPhone(null));
        assertEquals("***", RecipientMasker.maskPhone(""));
    }
}
