package com.spring_cgv_24th.domain.store.entity;

import com.spring_cgv_24th.domain.theater.entity.Theater;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
        this.theater = theater;
        this.product = product;
        this.quantity = quantity;
    }

}
