package com.paycore.transactionservice.service;

import com.paycore.transactionservice.domain.enums.TransactionStatus;
import com.paycore.transactionservice.dto.DepositRequest;
import com.paycore.transactionservice.dto.TransactionResponse;
import com.paycore.transactionservice.dto.TransferRequest;
import com.paycore.transactionservice.dto.WithdrawRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface TransactionService {

    TransactionResponse initiateTransfer(UUID userId, String idempotencyKey, TransferRequest request);

    TransactionResponse initiateDeposit(UUID userId, String idempotencyKey, DepositRequest request);

    TransactionResponse initiateWithdraw(UUID userId, String idempotencyKey, WithdrawRequest request);

    TransactionResponse getTransactionById(UUID userId, String role, UUID transactionId);

    Page<TransactionResponse> getTransactions(UUID userId, String role, TransactionStatus status, Pageable pageable);
}
