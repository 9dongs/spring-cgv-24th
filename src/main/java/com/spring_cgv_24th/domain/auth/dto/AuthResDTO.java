package com.spring_cgv_24th.domain.auth.dto;

import com.spring_cgv_24th.domain.member.entity.Member;
import com.spring_cgv_24th.domain.member.enums.MemberRole;
import com.spring_cgv_24th.global.security.principal.CustomUserDetails;

public class AuthResDTO {

    private AuthResDTO() {
    }

    public record SignUpResDTO(
            Long memberId,
            String email,
            String name,
            MemberRole role
    ) {
        public static SignUpResDTO from(Member member) {
            return new SignUpResDTO(
                    member.getId(),
                    member.getEmail(),
                    member.getName(),
                    member.getRole());
        }
    }

    public record LoginResDTO(
            Long memberId,
            String email,
            MemberRole role
    ) {
        public static LoginResDTO from(CustomUserDetails userDetails) {
            return new LoginResDTO(
                    userDetails.getMemberId(),
                    userDetails.getEmail(),
                    userDetails.getRole());
        }
    }
}
