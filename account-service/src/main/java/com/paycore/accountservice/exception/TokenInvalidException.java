package com.paycore.accountservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class TokenInvalidException extends RuntimeException {
    public TokenInvalidException() {
        super("Token is invalid, expired, or has been revoked");
    }
    public TokenInvalidException(String message) {
        super(message);
    }
}
