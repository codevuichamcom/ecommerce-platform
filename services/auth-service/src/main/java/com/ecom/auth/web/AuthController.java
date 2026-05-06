package com.ecom.auth.web;

import com.ecom.auth.domain.User;
import com.ecom.auth.repository.UserRepository;
import com.ecom.auth.security.AuthUserPrincipal;
import com.ecom.auth.service.AuthService;
import com.ecom.auth.web.dto.LoginRequest;
import com.ecom.auth.web.dto.MeResponse;
import com.ecom.auth.web.dto.RefreshRequest;
import com.ecom.auth.web.dto.RegisterRequest;
import com.ecom.auth.web.dto.TokenResponse;
import com.ecom.common.exception.BusinessException;
import com.ecom.common.exception.ErrorCode;
import com.ecom.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TokenResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResponse.ok(authService.register(req));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return ApiResponse.ok(authService.refresh(req.refreshToken()));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal AuthUserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Not authenticated");
        }
        User user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "User not found"));

        // Verify Virtual Threads thật sự bật — Day 2 modernity check.
        boolean vt = Thread.currentThread().isVirtual();
        return ApiResponse.ok(MeResponse.from(user, vt));
    }
}
