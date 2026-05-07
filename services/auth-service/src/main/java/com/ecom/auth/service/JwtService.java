package com.ecom.auth.service;

import com.ecom.auth.config.JwtProperties;
import com.ecom.auth.domain.User;
import com.ecom.auth.security.AuthUserPrincipal;
import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issue + verify JWT (HS256).
 *
 * <p>Claims:
 * <ul>
 *   <li>{@code sub}    user id (UUID string)</li>
 *   <li>{@code email}  email — convenience cho frontend</li>
 *   <li>{@code role}   USER / ADMIN</li>
 *   <li>{@code tv}     tokenVersion — bump để force-invalidate</li>
 *   <li>{@code iss/iat/exp} chuẩn</li>
 * </ul>
 *
 * <p>Lý do KHÔNG nhét sensitive data vào claim: JWT = base64(header).base64(payload).sig.
 * Payload là plaintext, ai có token đều decode được. Đừng nhét password / phone / address.
 */
@Service
public class JwtService {

    private final JwtProperties props;
    private final SecretKey signingKey;

    public JwtService(JwtProperties props) {
        this.props = props;
        byte[] keyBytes = props.secret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // jjwt 0.12 enforce; fail-fast tốt hơn để dev biết secret yếu.
            throw new IllegalStateException("auth.jwt.secret phải ≥ 32 bytes cho HS256");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /** Issue access token gắn user info hiện tại. */
    public String issueAccessToken(User user, Instant now) {
        Instant exp = now.plus(props.accessTokenTtl());
        return Jwts.builder()
                .issuer(props.issuer())
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("tv", user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public long accessTokenTtlSeconds() {
        return props.accessTokenTtl().toSeconds();
    }

    /**
     * Verify + parse. Throws {@link BusinessException} với code phân biệt
     * EXPIRED vs INVALID — frontend cần biết để trigger refresh flow.
     */
    public AuthUserPrincipal verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(props.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            UUID userId = UUID.fromString(claims.getSubject());
            String email = claims.get("email", String.class);
            String role = claims.get("role", String.class);
            Integer tv = claims.get("tv", Integer.class);
            return new AuthUserPrincipal(userId, email, role, tv == null ? 0 : tv);
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_EXPIRED, "Access token expired");
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "Invalid access token");
        }
    }
}
