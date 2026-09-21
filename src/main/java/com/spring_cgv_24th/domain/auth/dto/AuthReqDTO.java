package com.spring_cgv_24th.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthReqDTO {

    private AuthReqDTO() {
    }

    public record SignUpReqDTO(
            @NotBlank(message = "이메일은 필수입니다.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            @Size(max = 254, message = "이메일은 254자 이하이어야 합니다.")
            String email,

            @NotBlank(message = "이름은 필수입니다.")
            @Size(max = 50, message = "이름은 50자 이하이어야 합니다.")
            String name,

            @NotBlank(message = "비밀번호는 필수입니다.")
            @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하이어야 합니다.")
            String password
    ) {
    }

    public record LoginReqDTO(
            @NotBlank(message = "이메일은 필수입니다.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            String email,

            @NotBlank(message = "비밀번호는 필수입니다.")
            String password
    ) {
    }
}
