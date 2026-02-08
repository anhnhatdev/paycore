package com.paycore.accountservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown on wrong email/password.
 * Message MUST be generic — never reveal whether email exists.
 * Fintech security principle: prevent email enumeration attacks.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Email or password is incorrect");
    }
}
