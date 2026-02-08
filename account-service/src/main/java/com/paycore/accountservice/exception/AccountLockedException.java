package com.paycore.accountservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.LOCKED)
public class AccountLockedException extends RuntimeException {
    public AccountLockedException() {
        super("Account is locked due to too many failed login attempts. Please contact support.");
    }
    public AccountLockedException(String message) {
        super(message);
    }
}
