package com.spring_cgv_24th.domain.favorite.controller;

import com.spring_cgv_24th.domain.favorite.dto.MovieFavoriteResDTO;
import com.spring_cgv_24th.domain.favorite.service.MovieFavoriteService;
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

@Tag(name = "MovieFavorite", description = "영화 찜 추가·해제 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/movies/{movieId}/favorites")
public class MovieFavoriteController {

    private final MovieFavoriteService movieFavoriteService;

    @Operation(summary = "영화 찜 추가")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MovieFavoriteResDTO> addFavorite(
            @Positive @PathVariable("movieId") Long movieId,
            @Parameter(description = "임시로 사용하는 값")
            @Positive @RequestParam("memberId") Long memberId) {
        return ApiResponse.onCreated(movieFavoriteService.addFavorite(movieId, memberId));
    }

    @Operation(summary = "영화 찜 해제")
    @DeleteMapping
    public ApiResponse<Void> removeFavorite(
            @Positive @PathVariable("movieId") Long movieId,
            @Parameter(description = "임시로 사용하는 값")
            @Positive @RequestParam("memberId") Long memberId) {
        movieFavoriteService.removeFavorite(movieId, memberId);
        return ApiResponse.onSuccess(null);
    }
}
