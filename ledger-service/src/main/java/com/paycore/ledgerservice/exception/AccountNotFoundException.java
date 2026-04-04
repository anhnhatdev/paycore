package com.paycore.ledgerservice.exception;

public class AccountNotFoundException extends LedgerException {
    public AccountNotFoundException(String message) {
        super(message);
    }
}
