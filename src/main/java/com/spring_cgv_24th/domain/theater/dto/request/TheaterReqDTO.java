package com.spring_cgv_24th.domain.theater.dto.request;

import jakarta.validation.constraints.NotNull;

public class TheaterReqDTO {

    private TheaterReqDTO() {
    }

    public record CreateTheaterReqDTO(

            @NotNull
            String name,

            @NotNull
            String address
    ) {
    }
}

