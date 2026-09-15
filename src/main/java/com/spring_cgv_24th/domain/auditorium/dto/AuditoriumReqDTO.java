package com.spring_cgv_24th.domain.auditorium.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class AuditoriumReqDTO {
    private AuditoriumReqDTO() {
    }

    public record CreateAuditoriumDTO(
            @NotBlank(message = "상영관 이름은 필수입니다.")
            @Size(max = 50, message = "상영관 이름은 50자 이하이어야 합니다.")
            String name,

            @NotNull(message = "상영관 유형 ID는 필수입니다.")
            @Positive(message = "상영관 유형 ID는 양수이어야 합니다.")
            Long typeId
    ) {
    }
}
