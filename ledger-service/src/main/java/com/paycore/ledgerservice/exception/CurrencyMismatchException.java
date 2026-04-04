package com.paycore.ledgerservice.exception;

public class CurrencyMismatchException extends LedgerException {
    public CurrencyMismatchException(String message) {
        super(message);
    }
}
