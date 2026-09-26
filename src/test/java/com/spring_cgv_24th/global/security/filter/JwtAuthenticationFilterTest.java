package com.spring_cgv_24th.global.security.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.spring_cgv_24th.domain.member.enums.MemberRole;
import com.spring_cgv_24th.global.exception.CustomException;
import com.spring_cgv_24th.global.exception.ErrorCode;
import com.spring_cgv_24th.global.jwt.AccessTokenClaims;
import com.spring_cgv_24th.global.jwt.JwtProvider;
import com.spring_cgv_24th.global.security.principal.CustomUserDetails;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private JwtProvider jwtProvider;
    @Mock private AuthenticationEntryPoint authenticationEntryPoint;
    @Mock private FilterChain filterChain;

    private JwtAuthenticationFilter filter;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtProvider, authenticationEntryPoint);
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // Authorization 헤더가 없으면 인증을 만들지 않고 다음 필터로 넘긴다.
    @Test
    @DisplayName("Bearer Token이 없으면 인증 없이 다음 필터로 진행한다")
    void noToken() throws Exception {
        MockHttpServletRequest request = request("/api/reservations");

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtProvider);
        verify(filterChain).doFilter(request, response);
    }

    // 검증된 Claim을 SecurityContext의 인증 객체와 ROLE_USER 권한으로 옮긴다.
    @Test
    @DisplayName("검증된 토큰으로 인증 객체를 새 SecurityContext에 저장한다")
    void validToken() throws Exception {
        MockHttpServletRequest request = request("/api/reservations");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer access-token");
        when(jwtProvider.parseAccessToken("access-token"))
                .thenReturn(new AccessTokenClaims(1L, MemberRole.USER));

        filter.doFilter(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isInstanceOf(CustomUserDetails.class);
        CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
        assertThat(principal.getMemberId()).isEqualTo(1L);
        assertThat(principal.getPassword()).isNull();
        assertThat(principal.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
        verify(filterChain).doFilter(request, response);
    }

    // 만료된 토큰은 실패 원인을 보관하고 EntryPoint에서 응답한 뒤 체인을 멈춘다.
    @Test
    @DisplayName("검증 실패 원인을 요청에 저장하고 EntryPoint에서 응답을 처리한다")
    void invalidToken() throws Exception {
        MockHttpServletRequest request = request("/api/reservations");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer expired-token");
        when(jwtProvider.parseAccessToken("expired-token"))
                .thenThrow(new CustomException(ErrorCode.TOKEN_EXPIRED));

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE))
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
        verify(authenticationEntryPoint).commence(
                eq(request), eq(response), any(AuthenticationException.class));
        verifyNoInteractions(filterChain);
    }

    // Bearer가 아닌 인증 방식은 토큰 누락이 아닌 무효한 헤더로 취급한다.
    @Test
    @DisplayName("Bearer 형식이 아닌 인증 헤더는 TOKEN_INVALID로 처리한다")
    void invalidAuthorizationHeader() throws Exception {
        MockHttpServletRequest request = request("/api/admin/check");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic abc");

        filter.doFilter(request, response, filterChain);

        assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE))
                .isEqualTo(ErrorCode.TOKEN_INVALID);
        verifyNoInteractions(jwtProvider, filterChain);
        verify(authenticationEntryPoint).commence(
                eq(request), eq(response), any(AuthenticationException.class));
    }

    // 공개 API라도 잘못된 JWT가 명시되면 인증 실패로 즉시 응답한다.
    @Test
    @DisplayName("공개 API라도 잘못된 토큰이 있으면 EntryPoint에서 요청을 종료한다")
    void invalidTokenOnPublicApi() throws Exception {
        MockHttpServletRequest request = request("/api/movies");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer invalid-token");
        when(jwtProvider.parseAccessToken("invalid-token"))
                .thenThrow(new CustomException(ErrorCode.TOKEN_INVALID));

        filter.doFilter(request, response, filterChain);

        assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE))
                .isEqualTo(ErrorCode.TOKEN_INVALID);
        verify(authenticationEntryPoint).commence(
                eq(request), eq(response), any(AuthenticationException.class));
        verifyNoInteractions(filterChain);
    }

    // 로그인은 JWT 필터의 검사 대상에서 제외되어 헤더와 무관하게 다음 필터로 간다.
    @Test
    @DisplayName("로그인 경로에서는 JWT 필터를 실행하지 않는다")
    void skipLoginPath() throws Exception {
        MockHttpServletRequest request = request("/api/auth/login");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer access-token");

        filter.doFilter(request, response, filterChain);

        verifyNoInteractions(jwtProvider);
        verify(filterChain).doFilter(request, response);
    }

    private MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }
}
