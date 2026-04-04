package com.paycore.ledgerservice.exception;

public class IdempotencyPayloadMismatchException extends LedgerException {
    public IdempotencyPayloadMismatchException(String message) {
        super(message);
    }
}
