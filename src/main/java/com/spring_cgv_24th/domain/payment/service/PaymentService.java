package com.spring_cgv_24th.domain.payment.service;

import com.spring_cgv_24th.domain.payment.dto.PaymentReqDTO;
import com.spring_cgv_24th.domain.payment.dto.PaymentResDTO;
import com.spring_cgv_24th.domain.payment.entity.Payment;
import com.spring_cgv_24th.domain.payment.enums.PaymentStatus;
import com.spring_cgv_24th.domain.payment.enums.PaymentTargetType;
import com.spring_cgv_24th.domain.payment.gateway.PaymentGateway;
import com.spring_cgv_24th.domain.payment.repository.PaymentRepository;
import com.spring_cgv_24th.domain.payment.target.PaymentTarget;
import com.spring_cgv_24th.domain.reservation.entity.Reservation;
import com.spring_cgv_24th.domain.reservation.repository.ReservationRepository;
import com.spring_cgv_24th.domain.store.entity.StoreOrderItem;
import com.spring_cgv_24th.domain.store.repository.StoreOrderItemRepository;
import com.spring_cgv_24th.domain.store.repository.StoreOrderRepository;
import com.spring_cgv_24th.global.exception.CustomException;
import com.spring_cgv_24th.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PaymentService {
    private final StoreOrderRepository orderRepository;
    private final StoreOrderItemRepository itemRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway gateway;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public PaymentService(StoreOrderRepository orderRepository, StoreOrderItemRepository itemRepository,
                          ReservationRepository reservationRepository, PaymentRepository paymentRepository,
                          PaymentGateway gateway, Clock clock, PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.reservationRepository = reservationRepository;
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public PaymentResDTO confirm(Long memberId, PaymentTargetType type, Long targetId, PaymentReqDTO request) {
        Prepared prepared;
        try {
            prepared = transaction.execute(status -> prepare(memberId, type, targetId, request));
        } catch (DataIntegrityViolationException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && violation.getConstraintName() != null
                        && (violation.getConstraintName().contains("uk_payment_target")
                        || violation.getConstraintName().contains("uk_payment_key"))) {
                    throw new CustomException(ErrorCode.PAYMENT_REQUEST_CONFLICT);
                }
            }
            throw error;
        }
        PaymentResDTO current = prepared.payment();
        if (current.status() == PaymentStatus.COMPLETED) return current;

        // PG 호출은 준비 트랜잭션의 잠금을 해제한 뒤 실행한다.
        PaymentResDTO result;
        try {
            PaymentGateway.Result response = prepared.approve()
                    ? gateway.confirm(current.paymentKey(), current.paymentOrderId(), current.amount(),
                    "confirm-" + current.paymentOrderId())
                    : gateway.find(current.paymentKey());
            result = applyResult(current, response);
        } catch (PaymentGateway.Failure failure) {
            result = handleFailure(current, prepared.approve() && failure.isRejected());
        }
        if (result.status() == PaymentStatus.FAILED) {
            throw new CustomException(ErrorCode.PAYMENT_CONFIRMATION_FAILED);
        }
        if (result.status() != PaymentStatus.COMPLETED) {
            throw new CustomException(ErrorCode.PAYMENT_PENDING);
        }
        return result;
    }

    public PaymentResDTO getPayment(Long memberId, PaymentTargetType type, Long targetId) {
        PaymentResDTO current = transaction.execute(status -> {
            validateOwner(findTarget(type, targetId, false), memberId);
            return PaymentResDTO.from(requirePayment(type, targetId));
        });
        return refreshPayment(current);
    }

    public void recoverUnresolvedPayments() {
        List<PaymentResDTO> payments = transaction.execute(status -> paymentRepository.findUnresolvedPayments(
                        List.of(PaymentStatus.PROCESSING, PaymentStatus.UNKNOWN), LocalDateTime.now(clock))
                .stream().map(PaymentResDTO::from).toList());
        for (PaymentResDTO payment : payments) {
            try {
                refreshPayment(payment);
            } catch (RuntimeException error) {
                log.warn("미확정 결제 재조회 실패: targetType={}, targetId={}",
                        payment.targetType(), payment.targetId(), error);
            }
        }
    }

    private PaymentResDTO refreshPayment(PaymentResDTO current) {
        if (current.status() == PaymentStatus.COMPLETED || current.status() == PaymentStatus.FAILED) return current;
        try {
            return applyResult(current, gateway.find(current.paymentKey()));
        } catch (PaymentGateway.Failure failure) {
            return handleFailure(current, false);
        }
    }

    private Prepared prepare(Long memberId, PaymentTargetType type, Long targetId, PaymentReqDTO request) {
        PaymentTarget target = findTarget(type, targetId, true);
        validateOwner(target, memberId);
        Payment existing = paymentRepository.findByTargetTypeAndTargetId(type, targetId).orElse(null);
        long amount = existing != null ? existing.getAmount() : amountOf(target);
        if (!Objects.equals(target.getPaymentOrderId(), request.paymentOrderId())
                || (existing != null && !existing.getPaymentKey().equals(request.paymentKey()))) {
            throw new CustomException(ErrorCode.PAYMENT_REQUEST_CONFLICT);
        }
        if (amount != request.amount()) throw new CustomException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        if (existing != null) {
            if (existing.getStatus() == PaymentStatus.PROCESSING) throw new CustomException(ErrorCode.PAYMENT_PENDING);
            if (existing.getStatus() == PaymentStatus.FAILED) throw new CustomException(ErrorCode.PAYMENT_CONFIRMATION_FAILED);
            return new Prepared(PaymentResDTO.from(existing), false);
        }
        if (paymentRepository.existsByPaymentKey(request.paymentKey())) {
            throw new CustomException(ErrorCode.PAYMENT_REQUEST_CONFLICT);
        }
        target.beginPayment(LocalDateTime.now(clock));
        Payment payment = paymentRepository.saveAndFlush(new Payment(type, target, request.paymentKey(), amount));
        return new Prepared(PaymentResDTO.from(payment), true);
    }

    private long amountOf(PaymentTarget target) {
        if (target instanceof Reservation reservation) return reservation.getTotalPrice();
        return itemRepository.findAllByOrder_IdOrderByProduct_IdAsc(target.getId()).stream()
                .mapToLong(StoreOrderItem::getTotalPrice).sum();
    }

    private PaymentResDTO applyResult(PaymentResDTO current, PaymentGateway.Result response) {
        if (!matches(current, response)) {
            PaymentResDTO result = update(current, (target, payment) -> payment.markUnknown());
            if (result.status() == PaymentStatus.COMPLETED || result.status() == PaymentStatus.FAILED) return result;
            throw new CustomException(ErrorCode.PAYMENT_GATEWAY_ERROR);
        }
        return update(current, (target, payment) -> {
            switch (response.status()) {
                case "DONE" -> {
                    payment.complete(response.method(), response.approvedAt().toInstant(), response.balanceAmount());
                    target.completePayment();
                }
                case "ABORTED", "EXPIRED" -> {
                    payment.fail();
                    target.failPayment();
                }
                default -> payment.markUnknown();
            }
        });
    }

    private boolean matches(PaymentResDTO payment, PaymentGateway.Result response) {
        return response != null && payment.paymentKey().equals(response.paymentKey())
                && payment.paymentOrderId().equals(response.orderId())
                && payment.amount() == response.totalAmount() && "KRW".equals(response.currency())
                && response.status() != null && response.balanceAmount() >= 0
                && response.balanceAmount() <= payment.amount()
                && (!"DONE".equals(response.status()) || (response.approvedAt() != null && response.method() != null));
    }

    private PaymentResDTO handleFailure(PaymentResDTO current, boolean rejected) {
        PaymentResDTO result = update(current, (target, payment) -> {
            if (rejected) {
                payment.fail();
                target.failPayment();
            } else {
                payment.markUnknown();
            }
        });
        if (result.status() == PaymentStatus.COMPLETED || result.status() == PaymentStatus.FAILED) return result;
        throw new CustomException(ErrorCode.PAYMENT_GATEWAY_ERROR);
    }

    private PaymentResDTO update(PaymentResDTO current, BiConsumer<PaymentTarget, Payment> change) {
        return transaction.execute(status -> {
            PaymentTarget target = findTarget(current.targetType(), current.targetId(), true);
            Payment payment = requirePayment(current.targetType(), current.targetId());
            if (!payment.isTerminal()) change.accept(target, payment);
            return PaymentResDTO.from(payment);
        });
    }

    private Payment requirePayment(PaymentTargetType type, Long targetId) {
        return paymentRepository.findByTargetTypeAndTargetId(type, targetId)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    private PaymentTarget findTarget(PaymentTargetType type, Long id, boolean lock) {
        return switch (type) {
            case STORE_ORDER -> (lock ? orderRepository.findByIdForUpdate(id) : orderRepository.findById(id))
                    .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_TARGET_NOT_FOUND));
            case RESERVATION -> (lock ? reservationRepository.findByIdForUpdate(id) : reservationRepository.findById(id))
                    .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_TARGET_NOT_FOUND));
        };
    }

    private void validateOwner(PaymentTarget target, Long memberId) {
        if (!Objects.equals(target.getMember().getId(), memberId)) throw new CustomException(ErrorCode.PAYMENT_FORBIDDEN);
    }

    private record Prepared(PaymentResDTO payment, boolean approve) {
    }
}
