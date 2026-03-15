package com.paycore.ledgerservice.exception;

public class IdempotencyConflictException extends LedgerException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
