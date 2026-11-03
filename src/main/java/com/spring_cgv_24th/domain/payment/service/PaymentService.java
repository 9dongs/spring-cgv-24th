package com.spring_cgv_24th.domain.payment.service;

import com.spring_cgv_24th.domain.payment.dto.PaymentCancelReqDTO;
import com.spring_cgv_24th.domain.payment.dto.PaymentPartialCancelReqDTO;
import com.spring_cgv_24th.domain.payment.dto.PaymentReqDTO;
import com.spring_cgv_24th.domain.payment.dto.PaymentResDTO;
import com.spring_cgv_24th.domain.payment.entity.Payment;
import com.spring_cgv_24th.domain.payment.entity.PaymentCancellation;
import com.spring_cgv_24th.domain.payment.enums.PaymentStatus;
import com.spring_cgv_24th.domain.payment.enums.PaymentTargetType;
import com.spring_cgv_24th.domain.payment.gateway.PaymentGateway;
import com.spring_cgv_24th.domain.payment.repository.PaymentRepository;
import com.spring_cgv_24th.domain.payment.target.PaymentTarget;
import com.spring_cgv_24th.domain.reservation.entity.Reservation;
import com.spring_cgv_24th.domain.reservation.repository.ReservationRepository;
import com.spring_cgv_24th.domain.reservation.service.ReservationService;
import com.spring_cgv_24th.domain.store.entity.StoreOrder;
import com.spring_cgv_24th.domain.store.entity.StoreOrderItem;
import com.spring_cgv_24th.domain.store.repository.StoreOrderItemRepository;
import com.spring_cgv_24th.domain.store.repository.StoreOrderRepository;
import com.spring_cgv_24th.domain.store.service.StoreService;
import com.spring_cgv_24th.global.exception.CustomException;
import com.spring_cgv_24th.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
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
    // 승인 요청 직후의 조회 결과로 만료를 확정하지 않도록 토스 결제 유효 시간만큼 기다린다.
    private static final long MISSING_PAYMENT_GRACE_MINUTES = 30;
    private final StoreOrderRepository orderRepository;
    private final StoreOrderItemRepository itemRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway gateway;
    private final StoreService storeService;
    private final ReservationService reservationService;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public PaymentService(StoreOrderRepository orderRepository, StoreOrderItemRepository itemRepository,
                          ReservationRepository reservationRepository, PaymentRepository paymentRepository,
                          PaymentGateway gateway, StoreService storeService, ReservationService reservationService,
                          Clock clock, PlatformTransactionManager transactionManager) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.reservationRepository = reservationRepository;
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
        this.storeService = storeService;
        this.reservationService = reservationService;
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
        PaymentSnapshot snapshot = prepared.snapshot();
        PaymentResDTO current = snapshot.payment();
        if (current.status() == PaymentStatus.COMPLETED) return current;

        // PG 호출은 준비 트랜잭션의 잠금을 해제한 뒤 실행한다.
        PaymentResDTO result;
        if (prepared.approve()) {
            try {
                result = applyResult(snapshot, gateway.confirm(current.paymentKey(), current.paymentOrderId(),
                        current.amount(), "confirm-" + current.paymentOrderId()));
            } catch (PaymentGateway.Failure failure) {
                result = handleFailure(snapshot, failure.isRejected());
            }
        } else {
            result = refreshPayment(snapshot);
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
        PaymentSnapshot current = transaction.execute(status -> {
            validateOwner(findTarget(type, targetId, false), memberId);
            return PaymentSnapshot.from(requirePayment(type, targetId));
        });
        return refreshPayment(current);
    }

    public PaymentResDTO cancel(Long memberId, PaymentTargetType type, Long targetId, PaymentCancelReqDTO request) {
        return executeCancellation(transaction.execute(status ->
                prepareCancellation(memberId, type, targetId, request.cancelReason(), null)));
    }

    public PaymentResDTO cancelPartially(Long memberId, PaymentTargetType type, Long targetId,
                                        PaymentPartialCancelReqDTO request) {
        return executeCancellation(transaction.execute(status ->
                prepareCancellation(memberId, type, targetId, request.cancelReason(), request)));
    }

    private PreparedCancellation prepareCancellation(Long memberId, PaymentTargetType type, Long targetId,
                                                     String reason, PaymentPartialCancelReqDTO partial) {
        PaymentTarget target = findTarget(type, targetId, true);
        validateOwner(target, memberId);
        Payment payment = requirePayment(type, targetId);
        String requestId = partial == null ? "FULL" : partial.requestId().toString();
        Map<Long, Integer> items = partial == null ? Map.of() : cancellationItems(type, partial);
        PaymentCancellation cancellation = payment.getCancellations().reversed().stream()
                .filter(item -> partial == null ? item.isFullCancellation() : item.getRequestId().equals(requestId))
                .findFirst().orElse(null);
        if (partial == null && cancellation != null && cancellation.getFailedAt() != null) {
            cancellation = null;
        }
        if (cancellation != null) {
            if (partial != null && (!cancellation.getItems().equals(items) || !cancellation.getCancelReason().equals(reason))) {
                throw new CustomException(ErrorCode.PAYMENT_CANCEL_REQUEST_CONFLICT);
            }
            if (cancellation.getFailedAt() != null) {
                throw new CustomException(ErrorCode.PAYMENT_CANCELLATION_REJECTED);
            }
            if (cancellation.getCompletedAt() != null) return new PreparedCancellation(PaymentSnapshot.from(payment), false);
            if (payment.getStatus() == PaymentStatus.CANCEL_PROCESSING
                    || payment.getStatus() == PaymentStatus.CANCEL_REJECTED_VERIFY) throw new CustomException(ErrorCode.PAYMENT_PENDING);
        } else {
            if (partial == null && payment.getStatus() == PaymentStatus.CANCELLED) {
                return new PreparedCancellation(PaymentSnapshot.from(payment), false);
            }
            // 기존 전체 취소의 미확정 요청도 같은 멱등키로 이어간다.
            boolean legacyRetry = partial == null && payment.getCancellations().isEmpty()
                    && payment.getStatus() == PaymentStatus.CANCEL_UNKNOWN;
            if (!legacyRetry) {
                if (payment.getStatus().isCancellationPending()) throw new CustomException(ErrorCode.PAYMENT_PENDING);
                if (payment.getStatus() != PaymentStatus.COMPLETED && payment.getStatus() != PaymentStatus.PARTIALLY_CANCELLED) {
                    throw new CustomException(ErrorCode.PAYMENT_NOT_CANCELLABLE);
                }
                target.validatePaymentCancellation(LocalDateTime.now(clock));
            }
            long amount = partial == null ? payment.getBalanceAmount() : target instanceof Reservation reservation
                    ? reservationService.calculateCancellationAmount(reservation, items)
                    : storeService.calculateCancellationAmount((StoreOrder) target, items);
            if (amount <= 0 || amount > payment.getBalanceAmount()) throw new CustomException(ErrorCode.PAYMENT_CANCEL_INVALID);
            String newRequestId = partial == null && payment.getCancellations().stream()
                    .anyMatch(PaymentCancellation::isFullCancellation) ? UUID.randomUUID().toString() : requestId;
            cancellation = new PaymentCancellation(payment, newRequestId, legacyRetry ? payment.getCancelReason() : reason,
                    amount, partial == null, items, clock.instant());
            payment.addCancellation(cancellation);
            paymentRepository.saveAndFlush(payment);
        }
        // 최초 접수 때 검사한 상영 시각은 같은 요청의 재시도에서 다시 검사하지 않는다.
        payment.beginCancellation(cancellation.getCancelReason());
        return new PreparedCancellation(PaymentSnapshot.from(payment), true);
    }

    private Map<Long, Integer> cancellationItems(PaymentTargetType type, PaymentPartialCancelReqDTO request) {
        Map<Long, Integer> items = new TreeMap<>();
        if (type == PaymentTargetType.STORE_ORDER && request.screeningSeatIds().isEmpty()) {
            for (PaymentPartialCancelReqDTO.Item item : request.items()) {
                if (items.putIfAbsent(item.productId(), item.quantity()) != null) {
                    throw new CustomException(ErrorCode.PAYMENT_CANCEL_INVALID);
                }
            }
        } else if (type == PaymentTargetType.RESERVATION && request.items().isEmpty()) {
            for (Long seatId : request.screeningSeatIds()) {
                if (items.putIfAbsent(seatId, 1) != null) throw new CustomException(ErrorCode.PAYMENT_CANCEL_INVALID);
            }
        }
        if (items.isEmpty()) throw new CustomException(ErrorCode.PAYMENT_CANCEL_INVALID);
        return items;
    }

    private PaymentResDTO executeCancellation(PreparedCancellation prepared) {
        PaymentSnapshot snapshot = prepared.snapshot();
        PaymentResDTO current = snapshot.payment();
        if (!prepared.submit()) return current;
        String key = "cancel-" + current.paymentOrderId()
                + ("FULL".equals(snapshot.cancellationRequestId()) ? "" : "-" + snapshot.cancellationRequestId());

        PaymentResDTO result;
        try {
            PaymentGateway.Result response = snapshot.cancelAmount() == null
                    ? gateway.cancel(current.paymentKey(), current.cancelReason(), key)
                    : gateway.cancel(current.paymentKey(), current.cancelReason(), snapshot.cancelAmount(), key);
            result = applyResult(snapshot, response);
        } catch (PaymentGateway.Failure failure) {
            result = failure.isRejected() ? reconcileRejectedCancellation(snapshot) : handleFailure(snapshot, false);
        }
        if (result.status() != PaymentStatus.CANCELLED && result.status() != PaymentStatus.PARTIALLY_CANCELLED) {
            throw new CustomException(ErrorCode.PAYMENT_PENDING);
        }
        return result;
    }

    private PaymentResDTO reconcileRejectedCancellation(PaymentSnapshot snapshot) {
        PaymentResDTO current = snapshot.payment();
        PaymentResDTO pending = update(snapshot, (target, payment) -> payment.verifyCancellationRejection());
        PaymentResDTO result = refreshPayment(new PaymentSnapshot(pending, snapshot.cancellationId(),
                snapshot.cancellationRequestId(), snapshot.cancelAmount()));
        if (result.balanceAmount() == current.balanceAmount() && result.status().isTerminal()) {
            throw new CustomException(ErrorCode.PAYMENT_CANCELLATION_REJECTED);
        }
        return result;
    }

    public void recoverUnresolvedPayments() {
        List<PaymentSnapshot> payments = transaction.execute(status -> paymentRepository.findUnresolvedPayments(
                        List.of(PaymentStatus.PROCESSING, PaymentStatus.UNKNOWN),
                        List.of(PaymentStatus.CANCEL_PROCESSING, PaymentStatus.CANCEL_UNKNOWN,
                                PaymentStatus.CANCEL_REJECTED_VERIFY), LocalDateTime.now(clock))
                .stream().map(PaymentSnapshot::from).toList());
        for (PaymentSnapshot payment : payments) {
            try {
                refreshPayment(payment);
            } catch (RuntimeException error) {
                log.warn("미확정 결제 재조회 실패: targetType={}, targetId={}",
                        payment.payment().targetType(), payment.payment().targetId(), error);
            }
        }
    }

    private PaymentResDTO refreshPayment(PaymentSnapshot current) {
        if (current.payment().status().isTerminal()) return current.payment();
        try {
            return applyResult(current, gateway.find(current.payment().paymentKey()));
        } catch (PaymentGateway.Failure failure) {
            if (failure.isNotFound() && !current.payment().status().isCancellationPending()) {
                PaymentResDTO result = update(current, (target, payment) -> {
                    LocalDateTime expiresAt = target.getExpiresAt();
                    if (expiresAt != null && !LocalDateTime.now(clock)
                            .isBefore(expiresAt.plusMinutes(MISSING_PAYMENT_GRACE_MINUTES))) {
                        payment.fail();
                        target.failPayment();
                    } else {
                        payment.markUnknown();
                    }
                });
                if (result.status().isTerminal()) return result;
                throw new CustomException(ErrorCode.PAYMENT_GATEWAY_ERROR);
            }
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
            if (existing.getStatus() != PaymentStatus.COMPLETED && existing.getStatus() != PaymentStatus.UNKNOWN) {
                throw new CustomException(ErrorCode.PAYMENT_TARGET_NOT_PAYABLE);
            }
            return new Prepared(PaymentSnapshot.from(existing), false);
        }
        if (paymentRepository.existsByPaymentKey(request.paymentKey())) {
            throw new CustomException(ErrorCode.PAYMENT_REQUEST_CONFLICT);
        }
        target.beginPayment(LocalDateTime.now(clock));
        Payment payment = paymentRepository.saveAndFlush(new Payment(type, target, request.paymentKey(), amount));
        return new Prepared(PaymentSnapshot.from(payment), true);
    }

    private long amountOf(PaymentTarget target) {
        if (target instanceof Reservation reservation) return reservation.getTotalPrice();
        return itemRepository.findAllByOrder_IdOrderByProduct_IdAsc(target.getId()).stream()
                .mapToLong(StoreOrderItem::getTotalPrice).sum();
    }

    private PaymentResDTO applyResult(PaymentSnapshot current, PaymentGateway.Result response) {
        if (!matches(current.payment(), response)) {
            PaymentResDTO result = update(current, (target, payment) -> payment.markUnknown());
            if (result.status().isTerminal()) return result;
            throw new CustomException(ErrorCode.PAYMENT_GATEWAY_ERROR);
        }
        return update(current, (target, payment) -> {
            if (payment.getStatus().isCancellationPending()) {
                PaymentCancellation cancellation = payment.getLatestCancellation();
                long expectedBalance = cancellation == null ? 0 : cancellation.getBalanceBefore() - cancellation.getAmount();
                if (("CANCELED".equals(response.status()) || "PARTIAL_CANCELED".equals(response.status()))
                        && response.balanceAmount() == expectedBalance) {
                    Map<Long, Integer> items = cancellation == null ? Map.of() : cancellation.getItems();
                    if (target instanceof Reservation reservation) {
                        reservationService.cancelPaidReservation(reservation, items, expectedBalance == 0);
                    } else {
                        storeService.cancelPaidOrder((StoreOrder) target, items, expectedBalance == 0);
                    }
                    payment.cancel(expectedBalance);
                    if (cancellation != null) cancellation.complete(clock.instant());
                } else if (current.payment().status() == PaymentStatus.CANCEL_REJECTED_VERIFY
                        && response.balanceAmount() == payment.getBalanceAmount()
                        && ("DONE".equals(response.status()) || "PARTIAL_CANCELED".equals(response.status()))) {
                    cancellation.fail(clock.instant());
                    payment.rejectCancellation();
                } else {
                    payment.markUnknown();
                }
                return;
            }
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
                && (!"CANCELED".equals(response.status()) || response.balanceAmount() == 0)
                // 여러 번 취소하면 토스가 잔액 0원에도 PARTIAL_CANCELED를 반환할 수 있다.
                && (!"PARTIAL_CANCELED".equals(response.status()) || response.balanceAmount() < payment.amount())
                && (!"DONE".equals(response.status()) || (response.approvedAt() != null && response.method() != null));
    }

    private PaymentResDTO handleFailure(PaymentSnapshot current, boolean rejected) {
        PaymentResDTO result = update(current, (target, payment) -> {
            if (rejected) {
                payment.fail();
                target.failPayment();
            } else {
                payment.markUnknown();
            }
        });
        if (result.status().isTerminal()) return result;
        throw new CustomException(ErrorCode.PAYMENT_GATEWAY_ERROR);
    }

    private PaymentResDTO update(PaymentSnapshot snapshot, BiConsumer<PaymentTarget, Payment> change) {
        return transaction.execute(status -> {
            PaymentResDTO current = snapshot.payment();
            PaymentTarget target = findTarget(current.targetType(), current.targetId(), true);
            Payment payment = requirePayment(current.targetType(), current.targetId());
            if (!payment.isTerminal()
                    && payment.getStatus().isCancellationPending() == current.status().isCancellationPending()
                    && Objects.equals(payment.getLatestCancellation() == null ? null : payment.getLatestCancellation().getId(),
                                      snapshot.cancellationId())) {
                change.accept(target, payment);
            }
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

    private record PaymentSnapshot(PaymentResDTO payment, Long cancellationId, String cancellationRequestId,
                                   Long cancelAmount) {
        static PaymentSnapshot from(Payment payment) {
            PaymentCancellation cancellation = payment.getLatestCancellation();
            return new PaymentSnapshot(PaymentResDTO.from(payment),
                    cancellation == null ? null : cancellation.getId(),
                    cancellation == null ? null : cancellation.getRequestId(),
                    cancellation == null || cancellation.isFullCancellation() ? null : cancellation.getAmount());
        }
    }

    private record Prepared(PaymentSnapshot snapshot, boolean approve) {
    }

    private record PreparedCancellation(PaymentSnapshot snapshot, boolean submit) {
    }
}
