package com.paycore.ledgerservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LedgerServiceApplicationTests {

    @Test
    @DisplayName("Ledger Service Spring context loads successfully")
    void contextLoads() {
    }
}
