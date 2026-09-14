package com.spring_cgv_24th.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON500", "서버 오류가 발생했습니다."),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON400", "잘못된 요청입니다."),
    THEATER_NOT_FOUND(HttpStatus.NOT_FOUND, "THEATER404", "영화관을 찾을 수 없습니다.");
    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
