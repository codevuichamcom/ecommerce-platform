package com.ecom.auth.service;

import com.ecom.auth.domain.User;
import com.ecom.auth.repository.UserRepository;
import com.ecom.auth.service.RefreshTokenService.RotationResult;
import com.ecom.auth.web.dto.LoginRequest;
import com.ecom.auth.web.dto.RegisterRequest;
import com.ecom.auth.web.dto.TokenResponse;
import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Use case orchestration cho register / login / refresh.
 *
 * <p>Inject {@link Clock} để test deterministic (advance time, simulate
 * expired token mà không cần Thread.sleep).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepo;
    private final RefreshTokenService refreshService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Transactional
    public TokenResponse register(RegisterRequest req) {
        String email = req.email().toLowerCase();
        if (userRepo.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.AUTH_USER_EXISTS, "Email already registered");
        }

        User user = User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .passwordHash(passwordEncoder.encode(req.password()))
                .role(User.Role.USER)
                .tokenVersion(0)
                .build();
        userRepo.save(user);
        log.info("User registered: id={}, email={}", user.getId(), email);

        return issueTokens(user);
    }

    @Transactional
    public TokenResponse login(LoginRequest req) {
        String email = req.email().toLowerCase();
        User user = userRepo.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials"));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            // Cùng error message cho both branch — chống user enumeration.
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS, "Invalid credentials");
        }
        return issueTokens(user);
    }

    @Transactional
    public TokenResponse refresh(String refreshTokenPlaintext) {
        RotationResult rot = refreshService.rotate(refreshTokenPlaintext, clock.instant());
        User user = userRepo.findById(rot.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "Owner not found"));

        String access = jwtService.issueAccessToken(user, clock.instant());
        return TokenResponse.bearer(access, rot.newPlaintext(), jwtService.accessTokenTtlSeconds());
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private TokenResponse issueTokens(User user) {
        String access = jwtService.issueAccessToken(user, clock.instant());
        String refresh = refreshService.issue(user.getId(), clock.instant());
        return TokenResponse.bearer(access, refresh, jwtService.accessTokenTtlSeconds());
    }
}
