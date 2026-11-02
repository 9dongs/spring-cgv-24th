package com.spring_cgv_24th.domain.payment.enums;

public enum PaymentStatus {
    PROCESSING,
    UNKNOWN,
    COMPLETED,
    FAILED,
    CANCEL_PROCESSING,
    CANCEL_UNKNOWN,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public boolean isCancellationPending() {
        return this == CANCEL_PROCESSING || this == CANCEL_UNKNOWN;
    }
}
