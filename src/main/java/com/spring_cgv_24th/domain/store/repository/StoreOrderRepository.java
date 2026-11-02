package com.spring_cgv_24th.domain.store.repository;

import com.spring_cgv_24th.domain.store.entity.StoreOrder;
import com.spring_cgv_24th.domain.store.enums.StoreOrderStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreOrderRepository extends JpaRepository<StoreOrder, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from StoreOrder o where o.status = :status and o.expiresAt <= :now order by o.id")
    List<StoreOrder> findExpiredOrdersForUpdate(
            @Param("status") StoreOrderStatus status, @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from StoreOrder o where o.id = :id")
    Optional<StoreOrder> findByIdForUpdate(@Param("id") Long id);
}
