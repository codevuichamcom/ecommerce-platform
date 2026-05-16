package com.ecom.common.security;

import java.util.UUID;

/**
 * Principal được set vào Spring Security context sau khi JWT verify
 * thành công ở các verify-only service (product / inventory / cart / order).
 *
 * <p>Auth-service có {@code AuthUserPrincipal} riêng (4 field, kèm
 * {@code tokenVersion}) — không reuse class này vì auth-service là
 * issuer + cần invalidation per-user. Verify-only service trust signature
 * + {@code exp}, không cần tokenVersion.
 *
 * <p>Day 7 lift từ 4 service trùng nhau lên đây — xem
 * {@code docs/lessons/07-refactor-extract-discipline.md}.
 */
public record AuthUserPrincipal(UUID userId, String email, String role) {}
