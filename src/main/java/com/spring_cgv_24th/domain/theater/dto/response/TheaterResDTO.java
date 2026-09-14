package com.spring_cgv_24th.domain.theater.dto.response;

import com.spring_cgv_24th.domain.theater.entity.Theater;

public record TheaterResDTO(
        Long theaterId,
        String name,
        String address,
        String phoneNumber
) {

    public static TheaterResDTO from(Theater theater) {
        return new TheaterResDTO(
                theater.getId(),
                theater.getName(),
                theater.getAddress(),
                theater.getPhoneNumber());
    }
}
