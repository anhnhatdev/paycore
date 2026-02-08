package com.paycore.accountservice.service;

import com.paycore.accountservice.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates unique account numbers in format: PC + 12 random digits.
 * Uses SecureRandom (not Math.random) for unpredictability.
 * Retries on collision (extremely rare but handled).
 */
@Component
@RequiredArgsConstructor
public class AccountNumberGenerator {

    private static final String PREFIX = "PC";
    private static final int DIGIT_COUNT = 12;
    private static final int MAX_RETRIES = 5;

    private final AccountRepository accountRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        for (int i = 0; i < MAX_RETRIES; i++) {
            String number = PREFIX + generateDigits();
            if (!accountRepository.existsByAccountNumber(number)) {
                return number;
            }
        }
        throw new IllegalStateException("Failed to generate unique account number after " + MAX_RETRIES + " attempts");
    }

    private String generateDigits() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DIGIT_COUNT; i++) {
            sb.append(secureRandom.nextInt(10));
        }
        return sb.toString();
    }
}
