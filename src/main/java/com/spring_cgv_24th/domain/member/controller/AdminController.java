package com.spring_cgv_24th.domain.member.controller;

import com.spring_cgv_24th.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Operation(summary = "관리자 권한 확인")
    @GetMapping("/check")
    public ApiResponse<String> check() {
        return ApiResponse.onSuccess("관리자 권한이 확인되었습니다.");
    }
}
