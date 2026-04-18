package com.paycore.transactionservice.exception;

public class UnauthorizedTransactionAccessException extends RuntimeException {
    public UnauthorizedTransactionAccessException(String message) {
        super(message);
    }
}
