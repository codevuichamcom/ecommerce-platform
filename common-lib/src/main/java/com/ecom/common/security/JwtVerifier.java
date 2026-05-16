package com.ecom.common.security;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Verify HS256 JWT issued bởi auth-service. Shared HMAC secret.
 *
 * <p>KHÔNG check tokenVersion — verify-only service trust signature + exp.
 * Nếu cần force invalidate sớm thì rút ngắn access TTL ở auth-service
 * (đang 15min) hoặc chuyển sang JWKS asymmetric (RS256).
 *
 * <p>Throw {@link BusinessException} với {@link ErrorCode#AUTH_TOKEN_EXPIRED}
 * hoặc {@link ErrorCode#AUTH_TOKEN_INVALID} — caller (filter) map sang 401.
 */
public class JwtVerifier {

    private final SecretKey signingKey;
    private final JwtVerifyProperties props;

    public JwtVerifier(JwtVerifyProperties props) {
        this.props = props;
        byte[] keyBytes = props.secret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("auth.jwt.secret phải ≥ 32 bytes cho HS256");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public AuthUserPrincipal verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(props.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return new AuthUserPrincipal(
                    UUID.fromString(claims.getSubject()),
                    claims.get("email", String.class),
                    claims.get("role", String.class));
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_EXPIRED, "Access token expired");
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "Invalid access token");
        }
    }
}
