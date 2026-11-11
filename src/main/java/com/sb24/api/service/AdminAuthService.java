package com.sb24.api.service;

import com.sb24.api.audit.RequestContext;
import com.sb24.api.config.AppProperties;
import com.sb24.api.domain.AdminUser;
import com.sb24.api.repo.AdminUserRepository;
import com.sb24.api.security.JwtService;
import com.sb24.api.web.Dtos.AdminLoginRequest;
import com.sb24.api.web.Dtos.TokenResponse;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Back-office login. Separate table, separate endpoint, no refresh token (re-authenticate every 10 minutes), same lockout rules. */
@Service
public class AdminAuthService {
    private final AdminUserRepository admins;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final AppProperties props;
    private final String dummyHash;

    public AdminAuthService(AdminUserRepository admins, PasswordEncoder encoder, JwtService jwt, AuditService audit, AppProperties props) {
        this.admins = admins;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
        this.props = props;
        this.dummyHash = encoder.encode("timing-equaliser-not-a-real-password");
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse login(AdminLoginRequest r) {
        AdminUser a = admins.findByEmail(r.email().trim().toLowerCase()).orElse(null);
        Instant now = Instant.now();
        if (a == null || !a.isEnabled()) {
            encoder.matches(r.password(), dummyHash);
            audit.admin(null, "ADMIN_LOGIN", "FAILED", "ADMIN", null, "unknown or disabled");
            throw invalid();
        }
        if (a.isLocked(now)) {
            audit.admin(a.getId(), "ADMIN_LOGIN", "LOCKED", "ADMIN", a.getId().toString(), "attempt while locked");
            throw invalid();
        }
        if (!encoder.matches(r.password(), a.getPasswordHash())) {
            int attempts = a.getFailedAttempts() + 1;
            if (attempts >= props.security().maxFailedLogins()) {
                a.setLockedUntil(now.plus(props.security().lockDuration()));
                a.setFailedAttempts(0);
                audit.admin(a.getId(), "ADMIN_ACCOUNT_LOCK", "SUCCESS", "ADMIN", a.getId().toString(), "too many failed logins");
            } else {
                a.setFailedAttempts(attempts);
            }
            admins.save(a);
            audit.admin(a.getId(), "ADMIN_LOGIN", "FAILED", "ADMIN", a.getId().toString(), "bad password");
            throw invalid();
        }
        a.setFailedAttempts(0);
        a.setLockedUntil(null);
        a.setLastLoginAt(now);
        RequestContext ctx = RequestContext.current();
        a.setLastLoginIp(ctx == null ? null : ctx.ip());
        admins.save(a);
        audit.admin(a.getId(), "ADMIN_LOGIN", "SUCCESS", "ADMIN", a.getId().toString(), null);
        return new TokenResponse(jwt.issueAdminToken(a.getId()), null, "Bearer", props.security().accessTtl().toSeconds());
    }

    private static ApiException invalid() { return ApiException.unauthorized("Credenciais inválidas."); }
}
