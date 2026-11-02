package com.spring_cgv_24th.domain.payment.controller;

import com.spring_cgv_24th.domain.payment.dto.PaymentCancelReqDTO;
import com.spring_cgv_24th.domain.payment.dto.PaymentReqDTO;
import com.spring_cgv_24th.domain.payment.dto.PaymentResDTO;
import com.spring_cgv_24th.domain.payment.enums.PaymentTargetType;
import com.spring_cgv_24th.domain.payment.service.PaymentService;
import com.spring_cgv_24th.global.response.ApiResponse;
import com.spring_cgv_24th.global.security.principal.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Tag(name = "Payment", description = "매점 주문·영화 예매 공통 결제 API")
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/api/payments/{targetType}/{targetId}")
public class PaymentController {
    private final PaymentService paymentService;

    @Operation(summary = "결제 승인")
    @PostMapping("/confirm")
    public ApiResponse<PaymentResDTO> confirm(
            @PathVariable("targetType") PaymentTargetType targetType,
            @Positive @PathVariable("targetId") Long targetId,
            @Valid @RequestBody PaymentReqDTO request,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return ApiResponse.onSuccess(paymentService.confirm(principal.getMemberId(), targetType, targetId, request));
    }

    @Operation(summary = "결제 조회 및 미확정 결과 확인")
    @GetMapping
    public ApiResponse<PaymentResDTO> getPayment(
            @PathVariable("targetType") PaymentTargetType targetType,
            @Positive @PathVariable("targetId") Long targetId,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return ApiResponse.onSuccess(paymentService.getPayment(principal.getMemberId(), targetType, targetId));
    }

    @Operation(summary = "결제 전체 취소")
    @PostMapping("/cancel")
    public ApiResponse<PaymentResDTO> cancel(
            @PathVariable("targetType") PaymentTargetType targetType,
            @Positive @PathVariable("targetId") Long targetId,
            @Valid @RequestBody PaymentCancelReqDTO request,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return ApiResponse.onSuccess(paymentService.cancel(principal.getMemberId(), targetType, targetId, request));
    }
}
