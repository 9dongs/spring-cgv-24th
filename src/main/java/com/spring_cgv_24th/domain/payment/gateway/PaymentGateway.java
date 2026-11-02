package com.spring_cgv_24th.domain.payment.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;

public interface PaymentGateway {

    Result confirm(String paymentKey, String orderId, long amount, String idempotencyKey);

    Result find(String paymentKey);

    Result cancel(String paymentKey, String cancelReason, String idempotencyKey);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(String paymentKey, String orderId, String status, String currency,
                  long totalAmount, long balanceAmount, String method, OffsetDateTime approvedAt) {
    }

    class Failure extends RuntimeException {
        private final boolean rejected;

        public Failure(boolean rejected) {
            super(rejected ? "Payment rejected" : "Payment result unknown");
            this.rejected = rejected;
        }

        public boolean isRejected() {
            return rejected;
        }
    }
}
