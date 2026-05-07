package com.ecom.auth.web.dto;

import com.ecom.auth.domain.User;

import java.util.UUID;

public record MeResponse(UUID id, String email, String role, boolean virtualThread) {

    public static MeResponse from(User user, boolean virtualThread) {
        return new MeResponse(user.getId(), user.getEmail(), user.getRole().name(), virtualThread);
    }
}
