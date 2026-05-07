package com.ecom.product.security;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecom.product.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Verify JWT issued bởi auth-service. Shared HMAC secret — Day 7 sẽ
 * lift class này lên common-lib để loại duplicate.
 *
 * <p>KHÔNG check tokenVersion ở đây — đó là auth-service concern (cần
 * DB lookup user). Product-service trust signature + exp; nếu cần force
 * invalidate sớm thì rút ngắn access TTL (đang 15min).
 */
@Component
public class JwtVerifier {

    private final SecretKey signingKey;
    private final JwtProperties props;

    public JwtVerifier(JwtProperties props) {
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
