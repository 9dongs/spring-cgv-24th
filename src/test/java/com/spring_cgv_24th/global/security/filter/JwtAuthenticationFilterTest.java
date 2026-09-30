package com.spring_cgv_24th.global.security.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring_cgv_24th.domain.auth.controller.AuthController;
import com.spring_cgv_24th.domain.auth.dto.AuthReqDTO;
import com.spring_cgv_24th.domain.auth.dto.AuthResDTO;
import com.spring_cgv_24th.domain.auth.service.AuthService;
import com.spring_cgv_24th.domain.member.enums.MemberRole;
import com.spring_cgv_24th.global.exception.CustomException;
import com.spring_cgv_24th.global.exception.ErrorCode;
import com.spring_cgv_24th.global.exception.GlobalExceptionHandler;
import com.spring_cgv_24th.global.jwt.AccessTokenClaims;
import com.spring_cgv_24th.global.jwt.JwtProperties;
import com.spring_cgv_24th.global.jwt.JwtProvider;
import com.spring_cgv_24th.global.security.principal.CustomUserDetails;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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

    // 실제로 발급한 Refresh JWT를 일반 보호 API에 보내도 인증을 만들지 않고 실패 처리한다.
    @Test
    @DisplayName("Refresh Token으로 보호 API에 접근하면 인증 실패로 처리한다")
    void rejectsRealRefreshTokenOnProtectedApi() throws Exception {
        JwtProperties properties = new JwtProperties(
                Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().getEncoded()),
                "spring-cgv-24th", "spring-cgv-api", Duration.ofMinutes(15), Duration.ofDays(7));
        JwtProvider realProvider = new JwtProvider(properties, Clock.systemUTC());
        JwtAuthenticationFilter realFilter = new JwtAuthenticationFilter(realProvider, authenticationEntryPoint);
        MockHttpServletRequest request = request("/api/reservations");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + realProvider.createRefreshToken(1L));

        realFilter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE))
                .isEqualTo(ErrorCode.TOKEN_INVALID);
        verify(authenticationEntryPoint).commence(
                eq(request), eq(response), any(AuthenticationException.class));
        verifyNoInteractions(filterChain);
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

    // 재발급 요청은 Access 인증 없이 본문을 서비스로 전달하며 만료된 Access 헤더가 있어도 진행한다.
    @ParameterizedTest(name = "만료된 Access Token 헤더 포함: {0}")
    @ValueSource(booleans = {false, true})
    void refreshRequestUsesBodyTokenRegardlessOfExpiredAccessHeader(boolean includeExpiredAccessHeader)
            throws Exception {
        Instant now = Instant.parse("2026-09-30T06:00:00Z");
        JwtProperties properties = new JwtProperties(
                Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().getEncoded()),
                "spring-cgv-24th", "spring-cgv-api", Duration.ofMinutes(15), Duration.ofDays(7));
        JwtProvider realProvider = new JwtProvider(properties, Clock.fixed(now, ZoneOffset.UTC));
        JwtProvider oldIssuer = new JwtProvider(properties,
                Clock.fixed(now.minus(Duration.ofMinutes(16)), ZoneOffset.UTC));
        JwtAuthenticationFilter realFilter = new JwtAuthenticationFilter(realProvider, authenticationEntryPoint);
        AuthService authService = mock(AuthService.class);
        AuthReqDTO.RefreshReqDTO request = new AuthReqDTO.RefreshReqDTO(realProvider.createRefreshToken(1L));
        String newAccessToken = realProvider.createAccessToken(1L, MemberRole.USER);
        when(authService.refresh(request)).thenReturn(AuthResDTO.RefreshResDTO.from(newAccessToken));
        MockMvc mockMvc = authMvc(realFilter, authService);
        MockHttpServletRequestBuilder apiRequest = post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(request));
        if (includeExpiredAccessHeader) {
            apiRequest.header(HttpHeaders.AUTHORIZATION,
                    "Bearer " + oldIssuer.createAccessToken(1L, MemberRole.USER));
        }

        mockMvc.perform(apiRequest)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value(newAccessToken))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());

        verify(authService).refresh(request);
        verifyNoInteractions(authenticationEntryPoint);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // Access 헤더 검사를 생략해도 본문 Refresh Token의 검증 실패는 공통 JSON 401로 반환한다.
    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"REFRESH_TOKEN_EXPIRED", "REFRESH_TOKEN_INVALID"})
    void refreshValidationFailureStillReturnsCommonJson(ErrorCode errorCode) throws Exception {
        AuthService authService = mock(AuthService.class);
        AuthReqDTO.RefreshReqDTO request = new AuthReqDTO.RefreshReqDTO("invalid-refresh-token");
        when(authService.refresh(request)).thenThrow(new CustomException(errorCode));

        authMvc(filter, authService).perform(post("/api/auth/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expired-access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()))
                .andExpect(jsonPath("$.message").value(errorCode.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(authService).refresh(request);
        verifyNoInteractions(jwtProvider, authenticationEntryPoint);
    }

    // Access 인증을 요구하지 않는 재발급 API도 Refresh Token 필수 입력 검증은 서비스 실행 전에 적용한다.
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t"})
    void refreshRequestRequiresNonBlankBodyToken(String refreshToken) throws Exception {
        AuthService authService = mock(AuthService.class);

        authMvc(filter, authService).perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(new AuthReqDTO.RefreshReqDTO(refreshToken))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("COMMON400"));

        verifyNoInteractions(authService, jwtProvider, authenticationEntryPoint);
    }

    private MockMvc authMvc(JwtAuthenticationFilter requestFilter, AuthService authService) {
        return MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(requestFilter)
                .build();
    }

    private MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }
}
