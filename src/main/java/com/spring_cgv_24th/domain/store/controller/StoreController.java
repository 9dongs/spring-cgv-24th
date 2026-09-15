package com.spring_cgv_24th.domain.store.controller;

import com.spring_cgv_24th.domain.store.dto.ProductResDTO;
import com.spring_cgv_24th.domain.store.dto.TheaterStockResDTO;
import com.spring_cgv_24th.domain.store.service.StoreService;
import com.spring_cgv_24th.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Store", description = "매점 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class StoreController {

    private final StoreService storeService;

    @Operation(summary = "공통 매점 메뉴 조회")
    @GetMapping("/store/products")
    public ApiResponse<List<ProductResDTO>> getProducts() {
        return ApiResponse.onSuccess(storeService.getProducts());
    }

    @Operation(summary = "영화관별 매점 상품·재고 조회")
    @GetMapping("/theaters/{theaterId}/store/products")
    public ApiResponse<List<TheaterStockResDTO>> getTheaterProducts(
            @Positive @PathVariable("theaterId") Long theaterId) {
        return ApiResponse.onSuccess(storeService.getTheaterProducts(theaterId));
    }
}
