package com.paycore.transactionservice.exception;

public class LedgerServiceUnavailableException extends RuntimeException {
    public LedgerServiceUnavailableException(String message) {
        super(message);
    }
}
