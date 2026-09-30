package com.spring_cgv_24th.global.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spring_cgv_24th.domain.member.enums.MemberRole;
import com.spring_cgv_24th.global.exception.CustomException;
import com.spring_cgv_24th.global.exception.ErrorCode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

    private static final String ISSUER = "spring-cgv-24th";
    private static final String AUDIENCE = "spring-cgv-api";

    private SecretKey signingKey;
    private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        signingKey = Jwts.SIG.HS256.key().build();
        JwtProperties properties = new JwtProperties(
                Encoders.BASE64.encode(signingKey.getEncoded()),
                ISSUER,
                AUDIENCE,
                Duration.ofMinutes(15),
                Duration.ofDays(7));
        jwtProvider = new JwtProvider(properties, Clock.systemUTC());
    }

    // 발급한 Access Token에서 회원 ID와 USER 역할을 다시 읽을 수 있다.
    @Test
    @DisplayName("Access Token을 발급하고 검증된 회원 정보와 권한을 반환한다")
    void createAndParseAccessToken() {
        String token = jwtProvider.createAccessToken(1L, MemberRole.USER);

        AccessTokenClaims claims = jwtProvider.parseAccessToken(token);

        assertThat(claims.memberId()).isEqualTo(1L);
        assertThat(claims.role()).isEqualTo(MemberRole.USER);
    }

    // 서명이 올바르더라도 만료 시간이 지났으면 TOKEN_EXPIRED로 구분한다.
    @Test
    @DisplayName("만료된 토큰은 TOKEN_EXPIRED로 처리한다")
    void expiredToken() {
        Instant now = Instant.now();
        String token = createToken(
                signingKey,
                now.minus(Duration.ofMinutes(30)),
                now.minus(Duration.ofMinutes(15)));

        assertErrorCode(token, ErrorCode.TOKEN_EXPIRED);
    }

    // 토큰의 서명 부분이 바뀌면 TOKEN_INVALID로 거부한다.
    @Test
    @DisplayName("변조된 토큰은 TOKEN_INVALID로 처리한다")
    void tamperedToken() {
        String token = jwtProvider.createAccessToken(1L, MemberRole.USER);
        String[] parts = token.split("\\.");
        char replacement = parts[2].charAt(0) == 'a' ? 'b' : 'a';
        parts[2] = replacement + parts[2].substring(1);
        String tamperedToken = String.join(".", parts);

        assertErrorCode(tamperedToken, ErrorCode.TOKEN_INVALID);
    }

    // 같은 Claim이라도 서버와 다른 비밀키로 서명한 토큰은 거부한다.
    @Test
    @DisplayName("다른 키로 서명한 토큰은 TOKEN_INVALID로 처리한다")
    void tokenSignedWithDifferentKey() {
        SecretKey otherKey = Jwts.SIG.HS256.key().build();
        Instant now = Instant.now();
        String token = createToken(otherKey, now, now.plus(Duration.ofMinutes(15)));

        assertErrorCode(token, ErrorCode.TOKEN_INVALID);
    }

    // 예상한 발급자가 아닌 토큰은 유효한 서명이어도 거부한다.
    @Test
    @DisplayName("기대한 발급자와 다른 토큰은 TOKEN_INVALID로 처리한다")
    void tokenWithDifferentIssuer() {
        Instant now = Instant.now();
        String token = createToken(
                signingKey,
                now,
                now.plus(Duration.ofMinutes(15)),
                "other-issuer",
                AUDIENCE);

        assertErrorCode(token, ErrorCode.TOKEN_INVALID);
    }

    // 다른 API를 대상으로 발급된 토큰은 유효한 서명이어도 거부한다.
    @Test
    @DisplayName("기대한 대상과 다른 토큰은 TOKEN_INVALID로 처리한다")
    void tokenWithDifferentAudience() {
        Instant now = Instant.now();
        String token = createToken(
                signingKey,
                now,
                now.plus(Duration.ofMinutes(15)),
                ISSUER,
                "other-api");

        assertErrorCode(token, ErrorCode.TOKEN_INVALID);
    }

    // JWT 형식 자체가 깨진 문자열은 TOKEN_INVALID로 처리한다.
    @Test
    @DisplayName("JWT 형식이 아닌 문자열은 TOKEN_INVALID로 처리한다")
    void malformedToken() {
        assertErrorCode("not-a-jwt", ErrorCode.TOKEN_INVALID);
    }

    private String createToken(SecretKey key, Instant issuedAt, Instant expiresAt) {
        return createToken(key, issuedAt, expiresAt, ISSUER, AUDIENCE);
    }

    private String createToken(
            SecretKey key,
            Instant issuedAt,
            Instant expiresAt,
            String issuer,
            String audience) {
        return Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject("1")
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .claim("role", MemberRole.USER.name())
                .claim("tokenType", "ACCESS")
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    private void assertErrorCode(String token, ErrorCode expectedErrorCode) {
        assertThatThrownBy(() -> jwtProvider.parseAccessToken(token))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(expectedErrorCode);
    }
}
