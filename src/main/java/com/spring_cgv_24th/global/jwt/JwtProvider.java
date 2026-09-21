package com.spring_cgv_24th.global.jwt;

import com.spring_cgv_24th.domain.member.enums.MemberRole;
import com.spring_cgv_24th.global.exception.CustomException;
import com.spring_cgv_24th.global.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.MacAlgorithm;
import io.jsonwebtoken.security.WeakKeyException;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public class JwtProvider {

    private static final MacAlgorithm SIGNATURE_ALGORITHM = Jwts.SIG.HS256;
    private static final String ROLE_CLAIM = "role";
    private static final String TOKEN_TYPE_CLAIM = "tokenType";
    private static final String ACCESS_TOKEN_TYPE = "ACCESS";

    private final JwtProperties properties;
    private final SecretKey signingKey;
    private final JwtParser jwtParser;

    public JwtProvider(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = createSigningKey(properties.secret());
        this.jwtParser = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.issuer())
                .requireAudience(properties.audience())
                .require(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE)
                .sig()
                    .clear()
                    .add(SIGNATURE_ALGORITHM)
                    .and()
                .build();
    }

    public String createAccessToken(Long memberId, MemberRole role) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.accessTokenExpiration());

        return Jwts.builder()
                .issuer(properties.issuer())
                .audience().add(properties.audience()).and()
                .subject(memberId.toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .claim(ROLE_CLAIM, role.name())
                .claim(TOKEN_TYPE_CLAIM, ACCESS_TOKEN_TYPE)
                .signWith(signingKey, SIGNATURE_ALGORITHM)
                .compact();
    }

    public AccessTokenClaims parseAccessToken(String token) {
        try {
            Claims claims = jwtParser.parseSignedClaims(token).getPayload();
            return toAccessTokenClaims(claims);
        } catch (ExpiredJwtException e) {
            throw new CustomException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new CustomException(ErrorCode.TOKEN_INVALID);
        }
    }

    private AccessTokenClaims toAccessTokenClaims(Claims claims) {
        if (claims.getExpiration() == null || claims.getIssuedAt() == null) {
            throw new IllegalArgumentException("필수 시간 Claim이 없습니다.");
        }

        String subject = claims.getSubject();
        String roleClaim = claims.get(ROLE_CLAIM, String.class);
        if (subject == null || roleClaim == null) {
            throw new IllegalArgumentException("필수 사용자 Claim이 없습니다.");
        }

        Long memberId = Long.valueOf(subject);
        if (memberId <= 0) {
            throw new IllegalArgumentException("회원 ID가 올바르지 않습니다.");
        }

        MemberRole role = MemberRole.valueOf(roleClaim);
        return new AccessTokenClaims(memberId, role);
    }

    private SecretKey createSigningKey(String secret) {
        try {
            return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        } catch (DecodingException | WeakKeyException e) {
            throw new IllegalStateException(
                    "JWT_SECRET은 Base64로 인코딩된 256비트 이상의 키여야 합니다.", e);
        }
    }
}
