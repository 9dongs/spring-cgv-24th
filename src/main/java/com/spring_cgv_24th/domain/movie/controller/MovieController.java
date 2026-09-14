package com.spring_cgv_24th.domain.movie.controller;

import com.spring_cgv_24th.domain.movie.dto.response.MovieResDTO;
import com.spring_cgv_24th.domain.movie.service.MovieService;
import com.spring_cgv_24th.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Movie", description = "영화 조회 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/movies")
public class MovieController {

    private final MovieService movieService;

    @Operation(summary = "영화 상세 조회")
    @GetMapping("/{movieId}")
    public ApiResponse<MovieResDTO> getMovie(@PathVariable("movieId") Long movieId) {
        return ApiResponse.onSuccess(movieService.getMovie(movieId));
    }

    @Operation(summary = "영화 목록 조회")
    @GetMapping
    public ApiResponse<List<MovieResDTO>> getMovies() {
        return ApiResponse.onSuccess(movieService.getMovies());
    }
}
