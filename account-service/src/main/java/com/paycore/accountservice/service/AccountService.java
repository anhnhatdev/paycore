package com.paycore.accountservice.service;

import com.paycore.accountservice.dto.response.AccountResponse;
import com.paycore.accountservice.entity.Account;
import com.paycore.accountservice.event.AccountFrozenEvent;
import com.paycore.accountservice.exception.ResourceNotFoundException;
import com.paycore.accountservice.kafka.EventPublisher;
import com.paycore.accountservice.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private final AccountRepository accountRepository;
    private final EventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public List<AccountResponse> getAccountsByUserId(UUID userId) {
        return accountRepository.findByUserId(userId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountById(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId.toString()));
        return toResponse(account);
    }

    @Transactional(readOnly = true)
    public Account.AccountStatus getAccountStatus(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId.toString()));
        return account.getStatus();
    }

    /**
     * Freeze an account — ADMIN only.
     * Publishes AccountFrozen event so Transaction Service can reject new transactions immediately.
     * Uses @Version optimistic locking on Account entity to prevent race conditions.
     */
    @Transactional
    public AccountResponse freezeAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId.toString()));

        account.freeze();
        account = accountRepository.save(account);
        log.info("Account frozen: accountId={}", accountId);

        // Publish event — Transaction Service must react and block new transactions
        eventPublisher.publishAccountFrozen(AccountFrozenEvent.builder()
                .accountId(account.getId())
                .accountNumber(account.getAccountNumber())
                .userId(account.getUserId())
                .build());

        return toResponse(account);
    }

    private AccountResponse toResponse(Account account) {
        return AccountResponse.builder()
                .id(account.getId())
                .accountNumber(account.getAccountNumber())
                .currency(account.getCurrency())
                .status(account.getStatus().name())
                .createdAt(account.getCreatedAt())
                .build();
    }
}
