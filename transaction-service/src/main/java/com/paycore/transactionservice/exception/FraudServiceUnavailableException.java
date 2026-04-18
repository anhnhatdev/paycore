package com.paycore.transactionservice.exception;

public class FraudServiceUnavailableException extends RuntimeException {
    public FraudServiceUnavailableException(String message) {
        super(message);
    }
}
