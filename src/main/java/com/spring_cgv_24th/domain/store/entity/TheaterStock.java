package com.spring_cgv_24th.domain.store.entity;

import com.spring_cgv_24th.domain.theater.entity.Theater;
import com.spring_cgv_24th.global.exception.CustomException;
import com.spring_cgv_24th.global.exception.ErrorCode;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "theater_stock",
        uniqueConstraints = {@UniqueConstraint(columnNames = {"theater_id", "product_id"})})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TheaterStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "theater_stock_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "theater_id", nullable = false, foreignKey = @ForeignKey(name = "fk_theater_stock_theater"))
    private Theater theater;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, foreignKey = @ForeignKey(name = "fk_theater_stock_product"))
    private Product product;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp(6)")
    private LocalDateTime updatedAt;

    @Builder
    public TheaterStock(Theater theater, Product product, int quantity) {
        if (quantity < 1) {
            throw new CustomException(ErrorCode.BAD_REQUEST);
        }
        this.theater = theater;
        this.product = product;
        this.quantity = quantity;
        this.updatedAt = LocalDateTime.now();
    }

    public void decreaseQuantity(int purchaseQuantity) {
        if (purchaseQuantity <= 0) {
            throw new CustomException(ErrorCode.BAD_REQUEST);
        }
        // 구매 후에도 재고가 최소 1개 남아야 한다.
        if (quantity <= purchaseQuantity) {
            throw new CustomException(ErrorCode.STORE_STOCK_INSUFFICIENT);
        }

        quantity -= purchaseQuantity;
        updatedAt = LocalDateTime.now();
    }
}
