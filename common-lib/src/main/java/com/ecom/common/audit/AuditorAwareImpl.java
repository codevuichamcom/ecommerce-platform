package com.ecom.common.audit;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Lấy "current user" để JPA Auditing điền vào createdBy/updatedBy.
 *
 * <p>Logic:
 * <ul>
 *   <li>Có Spring Security context → dùng {@code authentication.getName()}.</li>
 *   <li>Anonymous / scheduled job → dùng "system".</li>
 * </ul>
 *
 * <p>Khi cần richer info (vd: userId UUID thay vì username), service tự
 * override bean này. Bean ở common-lib chỉ là default sane.
 */
public class AuditorAwareImpl implements AuditorAware<String> {

    private static final String SYSTEM = "system";

    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return Optional.of(SYSTEM);
        }
        return Optional.ofNullable(auth.getName());
    }
}
