package com.spring_cgv_24th.domain.payment.entity;

import com.spring_cgv_24th.domain.payment.enums.PaymentStatus;
import com.spring_cgv_24th.domain.payment.enums.PaymentTargetType;
import com.spring_cgv_24th.domain.payment.target.PaymentTarget;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "payment", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_target", columnNames = {"target_type", "target_id"}),
        @UniqueConstraint(name = "uk_payment_key", columnNames = "payment_key")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false)
    private PaymentTargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Column(name = "payment_order_id", nullable = false, updatable = false, length = 64)
    private String paymentOrderId;

    @Column(name = "payment_key", nullable = false, updatable = false,
            columnDefinition = "varchar(200) character set utf8mb4 collate utf8mb4_bin")
    private String paymentKey;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private String method;
    private Instant approvedAt;
    private long balanceAmount;

    public Payment(PaymentTargetType targetType, PaymentTarget target, String paymentKey, long amount) {
        this.targetType = targetType;
        this.targetId = target.getId();
        this.paymentOrderId = target.getPaymentOrderId();
        this.paymentKey = paymentKey;
        this.amount = amount;
        this.status = PaymentStatus.PROCESSING;
    }

    public boolean isTerminal() {
        return status == PaymentStatus.COMPLETED || status == PaymentStatus.FAILED;
    }

    public void complete(String method, Instant approvedAt, long balanceAmount) {
        status = PaymentStatus.COMPLETED;
        this.method = method;
        this.approvedAt = approvedAt;
        this.balanceAmount = balanceAmount;
    }

    public void fail() {
        status = PaymentStatus.FAILED;
    }

    public void markUnknown() {
        status = PaymentStatus.UNKNOWN;
    }
}
