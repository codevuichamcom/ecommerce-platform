package com.ecom.auth.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email
        String email,

        @NotBlank
        @Size(min = 8, max = 72, message = "Password must be 8-72 chars (BCrypt 72-byte limit)")
        String password
) {}
