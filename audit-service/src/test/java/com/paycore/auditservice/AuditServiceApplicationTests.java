package com.paycore.auditservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class AuditServiceApplicationTests {

    @Test
    @DisplayName("Spring application context loads successfully")
    void contextLoads() {
        assertTrue(true, "Audit service context should load without errors");
    }
}
