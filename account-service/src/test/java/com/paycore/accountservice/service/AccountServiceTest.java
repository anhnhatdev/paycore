package com.paycore.accountservice.service;

import com.paycore.accountservice.dto.response.AccountResponse;
import com.paycore.accountservice.entity.Account;
import com.paycore.accountservice.event.AccountFrozenEvent;
import com.paycore.accountservice.exception.ResourceNotFoundException;
import com.paycore.accountservice.kafka.EventPublisher;
import com.paycore.accountservice.repository.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private AccountService accountService;

    @Test
    @DisplayName("Freeze account should update status to FROZEN and publish AccountFrozen event")
    void freezeAccount_Success() {
        UUID accountId = UUID.randomUUID();
        Account account = Account.builder()
                .id(accountId)
                .userId(UUID.randomUUID())
                .accountNumber("PC123456789012")
                .currency("VND")
                .status(Account.AccountStatus.ACTIVE)
                .build();

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepository.save(account)).thenReturn(account);

        AccountResponse response = accountService.freezeAccount(accountId);

        assertNotNull(response);
        assertEquals("FROZEN", response.getStatus());
        assertEquals(Account.AccountStatus.FROZEN, account.getStatus());

        verify(accountRepository).save(account);
        verify(eventPublisher).publishAccountFrozen(any(AccountFrozenEvent.class));
    }

    @Test
    @DisplayName("Freeze non-existing account should throw ResourceNotFoundException")
    void freezeAccount_NotFound() {
        UUID nonExistingId = UUID.randomUUID();
        when(accountRepository.findById(nonExistingId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> accountService.freezeAccount(nonExistingId));
        verify(accountRepository, never()).save(any());
        verify(eventPublisher, never()).publishAccountFrozen(any());
    }
}
