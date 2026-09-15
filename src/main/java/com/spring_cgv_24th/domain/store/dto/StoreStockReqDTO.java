package com.spring_cgv_24th.domain.store.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record StoreStockReqDTO(
        @NotNull @Positive Integer quantity
) {
}
