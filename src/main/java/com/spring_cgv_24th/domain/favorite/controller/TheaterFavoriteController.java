package com.spring_cgv_24th.domain.favorite.controller;

import com.spring_cgv_24th.domain.favorite.dto.TheaterFavoriteResDTO;
import com.spring_cgv_24th.domain.favorite.service.TheaterFavoriteService;
import com.spring_cgv_24th.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "TheaterFavorite", description = "영화관 찜 추가·해제 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/theaters/{theaterId}/favorites")
public class TheaterFavoriteController {

    private final TheaterFavoriteService theaterFavoriteService;

    @Operation(summary = "영화관 찜 추가")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TheaterFavoriteResDTO> addFavorite(
            @Positive @PathVariable("theaterId") Long theaterId,
            @Parameter(description = "임시")
            @Positive @RequestParam("memberId") Long memberId) {
        return ApiResponse.onCreated(theaterFavoriteService.addFavorite(theaterId, memberId));
    }

    @Operation(summary = "영화관 찜 해제")
    @DeleteMapping
    public ApiResponse<Void> removeFavorite(
            @Positive @PathVariable("theaterId") Long theaterId,
            @Parameter(description = "임시")
            @Positive @RequestParam("memberId") Long memberId) {
        theaterFavoriteService.removeFavorite(theaterId, memberId);
        return ApiResponse.onSuccess(null);
    }
}
