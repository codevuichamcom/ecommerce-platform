package com.ecommerce.order.security;

import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecommerce.order.config.JwtProperties;
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
 * Mirror của product/inventory/cart JwtVerifier — Day 7 sẽ lift lên
 * common-lib. Tách riêng để Day 6 không touch foundation.
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
