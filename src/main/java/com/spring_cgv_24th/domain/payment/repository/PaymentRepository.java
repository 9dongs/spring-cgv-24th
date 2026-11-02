package com.spring_cgv_24th.domain.payment.repository;

import com.spring_cgv_24th.domain.payment.entity.Payment;
import com.spring_cgv_24th.domain.payment.enums.PaymentTargetType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByTargetTypeAndTargetId(PaymentTargetType targetType, Long targetId);
    boolean existsByPaymentKey(String paymentKey);
}
