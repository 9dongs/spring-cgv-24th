package com.spring_cgv_24th.domain.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spring_cgv_24th.domain.auth.dto.AuthReqDTO;
import com.spring_cgv_24th.domain.auth.dto.AuthResDTO;
import com.spring_cgv_24th.domain.auth.token.RefreshTokenHasher;
import com.spring_cgv_24th.domain.member.entity.Member;
import com.spring_cgv_24th.domain.member.enums.MemberRole;
import com.spring_cgv_24th.domain.member.repository.MemberRepository;
import com.spring_cgv_24th.global.exception.CustomException;
import com.spring_cgv_24th.global.exception.ErrorCode;
import com.spring_cgv_24th.global.jwt.JwtProperties;
import com.spring_cgv_24th.global.jwt.JwtProvider;
import com.spring_cgv_24th.global.security.principal.CustomUserDetails;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "Password123!";

    @Mock private MemberRepository memberRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtProvider jwtProvider;
    @Mock private RefreshTokenService refreshTokenService;
    @Captor private ArgumentCaptor<Member> memberCaptor;
    @Captor private ArgumentCaptor<Authentication> authenticationCaptor;
    @InjectMocks private AuthService authService;

    // 회원가입 시 비밀번호 원문 대신 인코딩 결과를 저장하고 역할을 USER로 고정한다.
    @Test
    void signUpSavesEncodedPasswordAndAlwaysUsesUserRole() {
        when(passwordEncoder.encode(PASSWORD)).thenReturn("encoded-password");
        when(memberRepository.save(any(Member.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AuthResDTO.SignUpResDTO response = authService.signUp(
                new AuthReqDTO.SignUpReqDTO(EMAIL, "테스트 회원", PASSWORD));

        verify(memberRepository).existsByEmail(EMAIL);
        verify(passwordEncoder).encode(PASSWORD);
        verify(memberRepository).save(memberCaptor.capture());
        Member savedMember = memberCaptor.getValue();
        assertEquals(EMAIL, savedMember.getEmail());
        assertEquals("테스트 회원", savedMember.getName());
        assertEquals("encoded-password", savedMember.getPasswordHash());
        assertEquals(MemberRole.USER, savedMember.getRole());
        assertEquals(EMAIL, response.email());
        assertEquals(MemberRole.USER, response.role());
        verifyNoInteractions(authenticationManager, jwtProvider, refreshTokenService);
    }

    // 이미 등록된 이메일이면 인코딩이나 저장을 시도하지 않는다.
    @Test
    void duplicateEmailDoesNotEncodeOrSavePassword() {
        when(memberRepository.existsByEmail(EMAIL)).thenReturn(true);

        CustomException error = assertThrows(CustomException.class, () -> authService.signUp(
                new AuthReqDTO.SignUpReqDTO(EMAIL, "테스트 회원", PASSWORD)));

        assertEquals(ErrorCode.MEMBER_EMAIL_ALREADY_EXISTS, error.getErrorCode());
        verify(memberRepository, never()).save(any(Member.class));
        verifyNoInteractions(passwordEncoder, authenticationManager, jwtProvider, refreshTokenService);
    }

    // 인증된 ID·역할로 Access Token을 만들고 같은 회원의 Refresh Token을 JSON 응답에 함께 넣는다.
    @Test
    void loginAuthenticatesCredentialsAndIssuesBothTokensForPrincipal() {
        CustomUserDetails principal = mock(CustomUserDetails.class);
        Authentication authenticated = mock(Authentication.class);
        when(principal.getMemberId()).thenReturn(7L);
        when(principal.getRole()).thenReturn(MemberRole.ADMIN);
        when(authenticated.getPrincipal()).thenReturn(principal);
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenReturn(authenticated);
        when(jwtProvider.createAccessToken(7L, MemberRole.ADMIN)).thenReturn("access-token");
        when(refreshTokenService.issue(7L)).thenReturn("refresh-token");

        AuthResDTO.LoginResDTO response = authService.login(
                new AuthReqDTO.LoginReqDTO(EMAIL, PASSWORD));

        verify(authenticationManager).authenticate(authenticationCaptor.capture());
        Authentication request = authenticationCaptor.getValue();
        assertTrue(request instanceof UsernamePasswordAuthenticationToken);
        assertFalse(request.isAuthenticated());
        assertEquals(EMAIL, request.getPrincipal());
        assertEquals(PASSWORD, request.getCredentials());
        verify(jwtProvider).createAccessToken(7L, MemberRole.ADMIN);
        verify(refreshTokenService).issue(7L);
        assertEquals("access-token", response.accessToken());
        assertEquals("refresh-token", response.refreshToken());
        JsonNode json = new ObjectMapper().valueToTree(response);
        assertEquals("access-token", json.path("accessToken").asText());
        assertEquals("refresh-token", json.path("refreshToken").asText());
        verifyNoInteractions(memberRepository, passwordEncoder);
    }

    // 실제 JWT 발급·해시 저장 로직을 연결하여 재로그인이 이전 Refresh Token을 대체하는지 확인한다.
    @Test
    void repeatedLoginStoresOnlyLatestRefreshTokenHash() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-30T06:00:00Z"), ZoneOffset.UTC);
        JwtProperties properties = new JwtProperties(
                Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().getEncoded()),
                "spring-cgv-24th", "spring-cgv-api", Duration.ofMinutes(15), Duration.ofDays(7));
        JwtProvider realProvider = new JwtProvider(properties, clock);
        RefreshTokenHasher hasher = new RefreshTokenHasher();
        RefreshTokenService realRefreshService = new RefreshTokenService(
                memberRepository, realProvider, hasher, clock);
        AuthService service = new AuthService(
                memberRepository, passwordEncoder, authenticationManager, realProvider, realRefreshService);
        Member member = Member.builder()
                .email(EMAIL)
                .name("테스트 회원")
                .passwordHash("encoded-password")
                .role(MemberRole.USER)
                .build();
        CustomUserDetails principal = mock(CustomUserDetails.class);
        Authentication authenticated = mock(Authentication.class);
        when(principal.getMemberId()).thenReturn(7L);
        when(principal.getRole()).thenReturn(MemberRole.USER);
        when(authenticated.getPrincipal()).thenReturn(principal);
        when(authenticationManager.authenticate(any(Authentication.class))).thenReturn(authenticated);
        when(memberRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));

        AuthReqDTO.LoginReqDTO request = new AuthReqDTO.LoginReqDTO(EMAIL, PASSWORD);
        AuthResDTO.LoginResDTO first = service.login(request);

        assertEquals(7L, realProvider.parseAccessToken(first.accessToken()).memberId());
        assertEquals(MemberRole.USER, realProvider.parseAccessToken(first.accessToken()).role());
        assertEquals(7L, realProvider.parseRefreshToken(first.refreshToken()).memberId());
        assertEquals(hasher.hash(first.refreshToken()), member.getRefreshTokenHash());
        assertNotEquals(first.refreshToken(), member.getRefreshTokenHash());

        AuthResDTO.LoginResDTO second = service.login(request);

        assertNotEquals(first.refreshToken(), second.refreshToken());
        assertEquals(hasher.hash(second.refreshToken()), member.getRefreshTokenHash());
        CustomException error = assertThrows(CustomException.class,
                () -> realRefreshService.findMemberByValidToken(first.refreshToken()));
        assertEquals(ErrorCode.REFRESH_TOKEN_INVALID, error.getErrorCode());
        assertSame(member, realRefreshService.findMemberByValidToken(second.refreshToken()));
    }

    // 인증 실패는 LOGIN_FAILED로 변환하고 두 토큰 모두 발급하지 않아 기존 해시도 변경하지 않는다.
    @Test
    void badCredentialsReturnLoginFailedWithoutIssuingToken() {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("인증 실패"));

        CustomException error = assertThrows(CustomException.class, () -> authService.login(
                new AuthReqDTO.LoginReqDTO(EMAIL, "wrong-password")));

        assertEquals(ErrorCode.LOGIN_FAILED, error.getErrorCode());
        verifyNoInteractions(jwtProvider, refreshTokenService, memberRepository, passwordEncoder);
    }
}
