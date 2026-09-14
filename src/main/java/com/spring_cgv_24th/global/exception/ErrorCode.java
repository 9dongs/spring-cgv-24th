package com.spring_cgv_24th.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON500", "서버 오류가 발생했습니다."),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON400", "잘못된 요청입니다."),
    THEATER_NOT_FOUND(HttpStatus.NOT_FOUND, "THEATER404", "영화관을 찾을 수 없습니다."),
    MOVIE_NOT_FOUND(HttpStatus.NOT_FOUND, "MOVIE404", "영화를 찾을 수 없습니다."),
    AUDITORIUM_NOT_FOUND(HttpStatus.NOT_FOUND, "AUDITORIUM404", "상영관을 찾을 수 없습니다."),
    SCREENING_NOT_FOUND(HttpStatus.NOT_FOUND, "SCREENING404", "상영 회차를 찾을 수 없습니다."),
    SCREENING_OVERLAP(HttpStatus.CONFLICT, "SCREENING409", "상영관의 다른 회차와 시간이 겹칩니다.");
    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
