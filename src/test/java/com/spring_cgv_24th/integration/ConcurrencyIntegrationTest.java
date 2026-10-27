package com.spring_cgv_24th.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.spring_cgv_24th.domain.reservation.dto.ReservationReqDTO;
import com.spring_cgv_24th.domain.reservation.service.ReservationService;
import com.spring_cgv_24th.domain.store.dto.StoreOrderReqDTO;
import com.spring_cgv_24th.domain.store.service.StoreService;
import com.spring_cgv_24th.global.exception.CustomException;
import com.spring_cgv_24th.global.exception.ErrorCode;
import java.sql.Statement;
import java.time.LocalDateTime;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

@SpringBootTest
class ConcurrencyIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReservationService reservationService;
    @Autowired private StoreService storeService;

    private long memberId, theaterId, auditoriumId, screeningId, seatId, productId;

    @BeforeEach
    void setUp() {
        memberId = insert("insert into member (email, name, password_hash, role) values (?, '동시성 회원', 'unused', 'USER')",
                UUID.randomUUID() + "@test.local");
        theaterId = insert("insert into theater (name, address) values ('동시성 영화관', '테스트')");
        long typeId = jdbc.queryForObject("select auditorium_type_id from auditorium_type where kind = 'GENERAL'", Long.class);
        auditoriumId = insert("insert into auditorium (theater_id, type_id, name) values (?, ?, '1관')", theaterId, typeId);
        long movieId = insert("insert into movie (title, duration_minutes, age_rating) values ('동시성 영화', 90, 'ALL')");
        LocalDateTime start = LocalDateTime.now().plusDays(1);
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
