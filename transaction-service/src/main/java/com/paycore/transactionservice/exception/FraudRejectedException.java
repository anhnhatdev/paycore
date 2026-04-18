package com.paycore.transactionservice.exception;

public class FraudRejectedException extends RuntimeException {
    public FraudRejectedException(String message) {
        super(message);
    }
}
