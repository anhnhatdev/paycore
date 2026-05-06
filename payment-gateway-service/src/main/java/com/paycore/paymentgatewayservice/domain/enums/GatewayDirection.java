package com.paycore.paymentgatewayservice.domain.enums;

public enum GatewayDirection {
    INBOUND,  // Deposit flow (User -> PayCore)
    OUTBOUND  // Withdraw flow (PayCore -> User bank)
}
