package com.paycore.ledgerservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PayCore Ledger Service
 * <p>
 * The Single Source of Truth for financial balances across PayCore.
 * Enforces immutable double-entry bookkeeping, 2-phase idempotency,
 * transactional outbox events, and balance reconciliation.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
public class LedgerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerServiceApplication.class, args);
    }
}
