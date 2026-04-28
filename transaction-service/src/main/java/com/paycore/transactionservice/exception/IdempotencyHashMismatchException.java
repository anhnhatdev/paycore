package com.paycore.transactionservice.exception;

public class IdempotencyHashMismatchException extends RuntimeException {
    public IdempotencyHashMismatchException(String message) {
        super(message);
    }
}
