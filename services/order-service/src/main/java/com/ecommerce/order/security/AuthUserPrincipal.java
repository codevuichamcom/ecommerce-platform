package com.ecommerce.order.security;

import java.util.UUID;

public record AuthUserPrincipal(UUID userId, String email, String role) {}
