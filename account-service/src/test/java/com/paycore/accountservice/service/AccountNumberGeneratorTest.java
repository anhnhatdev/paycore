package com.paycore.accountservice.service;

import com.paycore.accountservice.repository.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountNumberGeneratorTest {

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private AccountNumberGenerator accountNumberGenerator;

    @Test
    @DisplayName("Generated account number should match pattern PC followed by 12 digits")
    void generate_ShouldMatchExpectedFormat() {
        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);

        String accountNumber = accountNumberGenerator.generate();

        assertNotNull(accountNumber);
        assertTrue(accountNumber.matches("^PC\\d{12}$"), 
                "Account number format must be PC + 12 digits, got: " + accountNumber);
    }
}
