package com.spring_cgv_24th.domain.store.dto;

import com.spring_cgv_24th.domain.store.entity.Product;
import com.spring_cgv_24th.domain.store.entity.TheaterStock;

public record TheaterStockResDTO(
        Long productId,
        String name,
        int price,
        String description,
        String imageUrl,
        int quantity
) {

    public static TheaterStockResDTO from(TheaterStock stock) {
        Product product = stock.getProduct();
        return new TheaterStockResDTO(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getDescription(),
                product.getImageUrl(),
                stock.getQuantity());
    }
}
