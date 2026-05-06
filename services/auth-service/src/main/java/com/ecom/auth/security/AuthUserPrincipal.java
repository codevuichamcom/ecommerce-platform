package com.ecom.auth.security;

import java.util.UUID;

/**
 * Principal lưu trong SecurityContext sau khi JWT verify thành công.
 * Record → immutable, không cần getter boilerplate.
 */
public record AuthUserPrincipal(UUID userId, String email, String role, int tokenVersion) {}
