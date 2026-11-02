package com.spring_cgv_24th.domain.payment.enums;

public enum PaymentStatus {
    PROCESSING,
    UNKNOWN,
    COMPLETED,
    PARTIALLY_CANCELLED,
    FAILED,
    CANCEL_PROCESSING,
    CANCEL_UNKNOWN,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIALLY_CANCELLED || this == FAILED || this == CANCELLED;
    }

    public boolean isCancellationPending() {
        return this == CANCEL_PROCESSING || this == CANCEL_UNKNOWN;
    }
}
