package com.paycore.ledgerservice.exception;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class InsufficientBalanceException extends LedgerException {
    private final BigDecimal availableBalance;

    public InsufficientBalanceException(String message, BigDecimal availableBalance) {
        super(message);
        this.availableBalance = availableBalance;
    }
}
