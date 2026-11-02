package com.spring_cgv_24th.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.spring_cgv_24th.domain.payment.dto.PaymentReqDTO;
import com.spring_cgv_24th.domain.payment.enums.PaymentStatus;
import com.spring_cgv_24th.domain.payment.enums.PaymentTargetType;
import com.spring_cgv_24th.domain.payment.gateway.PaymentGateway;
import com.spring_cgv_24th.domain.payment.service.PaymentService;
import com.spring_cgv_24th.domain.reservation.dto.ReservationReqDTO;
import com.spring_cgv_24th.domain.reservation.service.ReservationService;
import com.spring_cgv_24th.domain.store.dto.StoreOrderReqDTO;
import com.spring_cgv_24th.domain.store.service.StoreService;
import com.spring_cgv_24th.global.exception.CustomException;
import com.spring_cgv_24th.global.exception.ErrorCode;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
class ConcurrencyIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReservationService reservationService;
    @Autowired private StoreService storeService;
    @Autowired private PaymentService paymentService;
    @MockitoBean private Clock clock;
    @MockitoBean private PaymentGateway gateway;

    private long memberId, theaterId, auditoriumId, screeningId, seatId, productId;

    @BeforeEach
    void setUp() {
        when(clock.getZone()).thenReturn(ZONE);
        when(clock.instant()).thenReturn(NOW.atZone(ZONE).toInstant());
        memberId = insert("insert into member (email, name, password_hash, role) values (?, '동시성 회원', 'unused', 'USER')",
                UUID.randomUUID() + "@test.local");
        theaterId = insert("insert into theater (name, address) values ('동시성 영화관', '테스트')");
        long typeId = jdbc.queryForObject("select auditorium_type_id from auditorium_type where kind = 'GENERAL'", Long.class);
        auditoriumId = insert("insert into auditorium (theater_id, type_id, name) values (?, ?, '1관')", theaterId, typeId);
        long movieId = insert("insert into movie (title, duration_minutes, age_rating) values ('동시성 영화', 90, 'ALL')");
        LocalDateTime start = NOW.plusDays(1);
        screeningId = insert("insert into screening (movie_id, auditorium_id, starts_at, ends_at) values (?, ?, ?, ?)",
                movieId, auditoriumId, start, start.plusMinutes(90));
        seatId = insert("insert into screening_seat (screening_id, row_no, column_no, price) values (?, 1, 1, 14000)", screeningId);
        productId = insert("insert into product (name, price) values ('팝콘', 5000)");
        insert("insert into theater_stock (theater_id, product_id, quantity, updated_at) values (?, ?, 6, CURRENT_TIMESTAMP)",
                theaterId, productId);
    }

    @Test
    void sameSeatCanOnlyBeReservedOnce() throws Exception {
        var request = new ReservationReqDTO.CreateReservationDTO(screeningId, List.of(seatId));

        var results = runTogether(10, () -> reservationService.createReservation(memberId, request));

        assertResults(results, 1, ErrorCode.SEAT_ALREADY_RESERVED);
        assertThat(count("select count(*) from reservation where screening_id = ?", screeningId)).isEqualTo(1);
        assertThat(count("select count(*) from screening_seat where screening_seat_id = ? and reservation_id is not null", seatId))
                .isEqualTo(1);
        assertThat(count("select count(*) from reservation_seat where screening_seat_id = ?", seatId)).isEqualTo(1);
    }

    @Test
    void concurrentOrdersKeepStockAndPurchasedQuantityConsistent() throws Exception {
        var request = new StoreOrderReqDTO.CreateOrderDTO(List.of(new StoreOrderReqDTO.OrderItemDTO(productId, 1)));

        var results = runTogether(10, () -> storeService.createOrder(theaterId, memberId, request));

        assertResults(results, 5, ErrorCode.STORE_STOCK_INSUFFICIENT);
        assertThat(count("select quantity from theater_stock where theater_id = ?", theaterId)).isEqualTo(1);
        assertThat(count("select count(*) from store_order where theater_id = ?", theaterId)).isEqualTo(5);
        assertThat(count("select sum(quantity) from store_order_item where product_id = ?", productId)).isEqualTo(5);
    }

    @ParameterizedTest
    @CsvSource({"9, 4, PENDING_PAYMENT", "10, 6, EXPIRED"})
    void concurrentExpirationRestoresStockOnlyOnceAfterTenMinutes(
            int elapsedMinutes, int expectedStock, String expectedStatus) throws Exception {
        var request = new StoreOrderReqDTO.CreateOrderDTO(List.of(new StoreOrderReqDTO.OrderItemDTO(productId, 2)));
        long orderId = storeService.createOrder(theaterId, memberId, request).orderId();
        when(clock.instant()).thenReturn(NOW.plusMinutes(elapsedMinutes).atZone(ZONE).toInstant());

        var results = runTogether(10, () -> {
            storeService.releaseUnpaidOrderStock();
            return null;
        });
        storeService.releaseUnpaidOrderStock();

        assertThat(results).containsOnlyNulls();
        assertThat(count("select quantity from theater_stock where theater_id = ?", theaterId)).isEqualTo(expectedStock);
        assertThat(jdbc.queryForObject("select status from store_order where store_order_id = ?", String.class, orderId))
                .isEqualTo(expectedStatus);
    }

    @Test
    void completedOrdersDoNotExpireOrRestoreStock() {
        var request = new StoreOrderReqDTO.CreateOrderDTO(List.of(new StoreOrderReqDTO.OrderItemDTO(productId, 2)));
        long orderId = storeService.createOrder(theaterId, memberId, request).orderId();
        jdbc.update("update store_order set status = 'COMPLETED', expires_at = null where store_order_id = ?", orderId);
        when(clock.instant()).thenReturn(NOW.plusMinutes(10).atZone(ZONE).toInstant());

        storeService.releaseUnpaidOrderStock();

        assertThat(count("select quantity from theater_stock where theater_id = ?", theaterId)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select status from store_order where store_order_id = ?", String.class, orderId))
                .isEqualTo("COMPLETED");
    }

    @ParameterizedTest
    @CsvSource({"9, 1, PENDING_PAYMENT", "10, 0, EXPIRED"})
    void unpaidReservationReleasesSeatsOnlyAfterTenMinutes(int minutes, int occupied, String status) throws Exception {
        Target target = paymentTarget(PaymentTargetType.RESERVATION);
        when(clock.instant()).thenReturn(NOW.plusMinutes(minutes).atZone(ZONE).toInstant());

        assertThat(runTogether(10, () -> { reservationService.releaseUnpaidSeats(); return null; })).containsOnlyNulls();
        reservationService.releaseUnpaidSeats();

        assertThat(count("select count(*) from screening_seat where screening_seat_id = ? and reservation_id is not null", seatId))
                .isEqualTo(occupied);
        assertThat(targetStatus(target)).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(PaymentTargetType.class)
    void invalidPaymentRequestsAreRejectedBeforeCallingToss(PaymentTargetType type) {
        Target target = paymentTarget(type);
        assertThat(assertThrows(CustomException.class,
                () -> paymentService.confirm(memberId + 1, type, target.id(), paymentRequest(target))).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_FORBIDDEN);
        assertThat(assertThrows(CustomException.class, () -> paymentService.confirm(memberId, type, target.id(),
                new PaymentReqDTO("payment-key", target.orderId(), 1L))).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        assertThat(assertThrows(CustomException.class, () -> paymentService.confirm(memberId, type, target.id(),
                new PaymentReqDTO("payment-key", "another-order", target.amount()))).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_REQUEST_CONFLICT);
        when(clock.instant()).thenReturn(NOW.plusMinutes(10).atZone(ZONE).toInstant());
        assertThat(assertThrows(CustomException.class,
                () -> paymentService.confirm(memberId, type, target.id(), paymentRequest(target))).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_TARGET_NOT_PAYABLE);
        verifyNoInteractions(gateway);
    }

    @ParameterizedTest
    @EnumSource(PaymentTargetType.class)
    void concurrentConfirmationsCallTossOnceAndKeepReservedResources(PaymentTargetType type) throws Exception {
        Target target = paymentTarget(type);
        when(gateway.confirm(anyString(), anyString(), anyLong(), anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            when(clock.instant()).thenReturn(NOW.plusMinutes(10).atZone(ZONE).toInstant());
            releaseUnpaidResources();
            assertResources(target, true);
            if (type == PaymentTargetType.RESERVATION) {
                assertThat(assertThrows(CustomException.class,
                        () -> reservationService.cancelReservation(target.id(), memberId)).getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_PAYMENT_CANCELLATION_REQUIRED);
            }
            return approved(target, target.amount());
        });

        var results = runTogether(10, () -> paymentService.confirm(memberId, type, target.id(), paymentRequest(target)));

        assertThat(results.stream().filter(Objects::isNull).count()).isGreaterThanOrEqualTo(1);
        assertThat(results.stream().filter(Objects::nonNull)).containsOnly(ErrorCode.PAYMENT_PENDING);
        assertThat(paymentService.getPayment(memberId, type, target.id()).status()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(paymentService.confirm(memberId, type, target.id(), paymentRequest(target)).amount()).isEqualTo(target.amount());
        verify(gateway, times(1)).confirm("payment-key", target.orderId(), target.amount(), "confirm-" + target.orderId());
        if (type == PaymentTargetType.RESERVATION) {
            assertThat(assertThrows(CustomException.class,
                    () -> reservationService.cancelReservation(target.id(), memberId)).getErrorCode())
                    .isEqualTo(ErrorCode.RESERVATION_PAYMENT_CANCELLATION_REQUIRED);
            assertResources(target, true);
        }
        Target other = paymentTarget(type == PaymentTargetType.RESERVATION
                ? PaymentTargetType.STORE_ORDER : PaymentTargetType.RESERVATION);
        assertThat(assertThrows(CustomException.class,
                () -> paymentService.confirm(memberId, other.type(), other.id(), paymentRequest(other))).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_REQUEST_CONFLICT);
    }

    @ParameterizedTest
    @EnumSource(PaymentTargetType.class)
    void rejectedPaymentEndsTargetAndReleasesResourcesOnTheNextSweep(PaymentTargetType type) {
        Target target = paymentTarget(type);
        when(gateway.confirm(anyString(), anyString(), anyLong(), anyString())).thenThrow(new PaymentGateway.Failure(true));

        assertThat(assertThrows(CustomException.class,
                () -> paymentService.confirm(memberId, type, target.id(), paymentRequest(target))).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_CONFIRMATION_FAILED);
        assertThat(targetStatus(target)).isEqualTo("PAYMENT_FAILED");
        assertResources(target, true);

        releaseUnpaidResources();
        releaseUnpaidResources();

        assertThat(targetStatus(target)).isEqualTo("CANCELLED");
        assertResources(target, false);
        assertThat(paymentService.getPayment(memberId, type, target.id()).status()).isEqualTo(PaymentStatus.FAILED);
    }

    @ParameterizedTest
    @CsvSource({"STORE_ORDER, false", "RESERVATION, true"})
    void uncertainPaymentKeepsResourcesAndRecoversThroughLookup(PaymentTargetType type, boolean invalidResponse) {
        Target target = paymentTarget(type);
        when(gateway.confirm(anyString(), anyString(), anyLong(), anyString())).thenAnswer(invocation -> {
            if (invalidResponse) return approved(target, 1);
            throw new PaymentGateway.Failure(false);
        });
        assertThat(assertThrows(CustomException.class,
                () -> paymentService.confirm(memberId, type, target.id(), paymentRequest(target))).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_GATEWAY_ERROR);
        when(clock.instant()).thenReturn(NOW.plusMinutes(11).atZone(ZONE).toInstant());
        releaseUnpaidResources();
        assertThat(targetStatus(target)).isEqualTo("PAYMENT_PROCESSING");
        assertResources(target, true);

        when(gateway.find("payment-key")).thenReturn(approved(target, target.amount()));
        assertThat(paymentService.getPayment(memberId, type, target.id()).status()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @ParameterizedTest
    @CsvSource({
            "STORE_ORDER, PROCESSING, DONE, COMPLETED, true",
            "RESERVATION, UNKNOWN, DONE, CONFIRMED, true",
            "STORE_ORDER, UNKNOWN, EXPIRED, CANCELLED, false",
            "RESERVATION, PROCESSING, ABORTED, CANCELLED, false",
            "STORE_ORDER, UNKNOWN, ERROR, PAYMENT_PROCESSING, true",
            "RESERVATION, PROCESSING, ERROR, PAYMENT_PROCESSING, true"
    })
    void overduePaymentsAreAutomaticallyRechecked(PaymentTargetType type, String initialStatus,
                                                  String responseStatus, String expectedStatus, boolean resourcesHeld) {
        Target target = paymentTarget(type);
        when(gateway.confirm(anyString(), anyString(), anyLong(), anyString())).thenThrow(new PaymentGateway.Failure(false));
        assertThrows(CustomException.class, () -> paymentService.confirm(memberId, type, target.id(), paymentRequest(target)));
        jdbc.update("update payment set status = ? where target_type = ? and target_id = ?", initialStatus, type.name(), target.id());
        when(clock.instant()).thenReturn(NOW.plusMinutes(9).atZone(ZONE).toInstant());
        paymentService.recoverUnresolvedPayments();
        verify(gateway, never()).find(anyString());

        when(gateway.find("payment-key")).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (responseStatus.equals("ERROR")) throw new PaymentGateway.Failure(false);
            if (responseStatus.equals("DONE")) return approved(target, target.amount());
            return new PaymentGateway.Result("payment-key", target.orderId(), responseStatus, "KRW", target.amount(), 0, null, null);
        });
        when(clock.instant()).thenReturn(NOW.plusMinutes(10).atZone(ZONE).toInstant());
        paymentService.recoverUnresolvedPayments();
        releaseUnpaidResources();
        paymentService.recoverUnresolvedPayments();
        releaseUnpaidResources();

        assertThat(targetStatus(target)).isEqualTo(expectedStatus);
        assertResources(target, resourcesHeld);
        verify(gateway, times(responseStatus.equals("ERROR") ? 2 : 1)).find("payment-key");
    }

    @Test
    void failedLookupDoesNotBlockRecoveryOfOtherPayments() {
        Target order = paymentTarget(PaymentTargetType.STORE_ORDER);
        Target reservation = paymentTarget(PaymentTargetType.RESERVATION);
        when(gateway.confirm(anyString(), anyString(), anyLong(), anyString())).thenThrow(new PaymentGateway.Failure(false));
        assertThrows(CustomException.class,
                () -> paymentService.confirm(memberId, order.type(), order.id(), paymentRequest(order)));
        assertThrows(CustomException.class, () -> paymentService.confirm(memberId, reservation.type(), reservation.id(),
                new PaymentReqDTO("reservation-payment-key", reservation.orderId(), reservation.amount())));
        when(gateway.find("payment-key")).thenThrow(new PaymentGateway.Failure(false));
        when(gateway.find("reservation-payment-key")).thenReturn(new PaymentGateway.Result(
                "reservation-payment-key", reservation.orderId(), "DONE", "KRW", reservation.amount(), reservation.amount(),
                "카드", NOW.atZone(ZONE).toOffsetDateTime()));
        when(clock.instant()).thenReturn(NOW.plusMinutes(10).atZone(ZONE).toInstant());

        paymentService.recoverUnresolvedPayments();

        assertThat(targetStatus(order)).isEqualTo("PAYMENT_PROCESSING");
        assertThat(targetStatus(reservation)).isEqualTo("CONFIRMED");
        assertResources(order, true);
        assertResources(reservation, true);
    }

    private Target paymentTarget(PaymentTargetType type) {
        if (type == PaymentTargetType.STORE_ORDER) {
            var order = storeService.createOrder(theaterId, memberId,
                    new StoreOrderReqDTO.CreateOrderDTO(List.of(new StoreOrderReqDTO.OrderItemDTO(productId, 2))));
            return new Target(type, order.orderId(), order.paymentOrderId(), order.totalPrice());
        }
        var reservation = reservationService.createReservation(memberId,
                new ReservationReqDTO.CreateReservationDTO(screeningId, List.of(seatId)));
        return new Target(type, reservation.reservationId(), reservation.paymentOrderId(), reservation.totalPrice());
    }

    private PaymentReqDTO paymentRequest(Target target) {
        return new PaymentReqDTO("payment-key", target.orderId(), target.amount());
    }

    private PaymentGateway.Result approved(Target target, long amount) {
        return new PaymentGateway.Result("payment-key", target.orderId(), "DONE", "KRW", amount, amount,
                "카드", NOW.atZone(ZONE).toOffsetDateTime());
    }

    private void releaseUnpaidResources() {
        storeService.releaseUnpaidOrderStock();
        reservationService.releaseUnpaidSeats();
    }

    private void assertResources(Target target, boolean held) {
        if (target.type() == PaymentTargetType.STORE_ORDER) {
            assertThat(count("select quantity from theater_stock where theater_id = ?", theaterId)).isEqualTo(held ? 4 : 6);
        } else {
            assertThat(count("select count(*) from screening_seat where screening_seat_id = ? and reservation_id is not null", seatId))
                    .isEqualTo(held ? 1 : 0);
        }
    }

    private String targetStatus(Target target) {
        String sql = target.type() == PaymentTargetType.STORE_ORDER
                ? "select status from store_order where store_order_id = ?"
                : "select status from reservation where reservation_id = ?";
        return jdbc.queryForObject(sql, String.class, target.id());
    }

    private record Target(PaymentTargetType type, Long id, String orderId, long amount) {
    }

    // 각 요청은 서비스 프록시를 통과해 독립 트랜잭션으로 커밋한다.
    private List<ErrorCode> runTogether(int workers, Callable<?> action) throws Exception {
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(workers)) {
            List<Future<ErrorCode>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("동시 요청 시작 시간 초과");
                    try {
                        action.call();
                        return null;
                    } catch (CustomException error) {
                        return error.getErrorCode();
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ErrorCode> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
        }
    }

    private void assertResults(List<ErrorCode> results, int successes, ErrorCode failure) {
        assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(successes);
        assertThat(results.stream().filter(Objects::nonNull)).containsOnly(failure);
    }

    private long insert(String sql, Object... parameters) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private long count(String sql, Object... parameters) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, parameters));
    }

    @AfterEach
    void cleanUp() {
        Long movieId = jdbc.queryForObject("select movie_id from screening where screening_id = ?", Long.class, screeningId);
        jdbc.update("delete from payment where target_type = 'STORE_ORDER' and target_id in (select store_order_id from store_order where theater_id = ?)", theaterId);
        jdbc.update("delete from payment where target_type = 'RESERVATION' and target_id in (select reservation_id from reservation where member_id = ?)", memberId);
        jdbc.update("delete from store_order_item where product_id = ?", productId);
        jdbc.update("delete from store_order where theater_id = ?", theaterId);
        jdbc.update("delete from theater_stock where theater_id = ?", theaterId);
        jdbc.update("delete from product where product_id = ?", productId);
        jdbc.update("delete from reservation_seat where reservation_id in (select reservation_id from reservation where member_id = ?)", memberId);
        jdbc.update("update screening_seat set reservation_id = null where screening_id = ?", screeningId);
        jdbc.update("delete from reservation where member_id = ?", memberId);
        jdbc.update("delete from screening_seat where screening_id in (select screening_id from screening where auditorium_id = ?)", auditoriumId);
        jdbc.update("delete from screening where auditorium_id = ?", auditoriumId);
        jdbc.update("delete from auditorium where auditorium_id = ?", auditoriumId);
        jdbc.update("delete from movie where movie_id = ?", movieId);
        jdbc.update("delete from theater where theater_id = ?", theaterId);
        jdbc.update("delete from member where member_id = ?", memberId);
    }
}
