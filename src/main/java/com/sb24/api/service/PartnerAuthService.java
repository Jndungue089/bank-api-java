package com.sb24.api.service;

import com.sb24.api.config.AppProperties;
import com.sb24.api.domain.AuditLog;
import com.sb24.api.domain.Partner;
import com.sb24.api.repo.PartnerRepository;
import com.sb24.api.security.JwtService;
import com.sb24.api.web.Dtos.PartnerTokenRequest;
import com.sb24.api.web.Dtos.TokenResponse;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Machine-to-machine login for creditors (client id + secret -> short-lived RS256 token, realm "partner"). Same lockout and timing rules as people. */
@Service
public class PartnerAuthService {
    private final PartnerRepository partners;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final AppProperties props;
    private final String dummyHash;

    public PartnerAuthService(PartnerRepository partners, PasswordEncoder encoder, JwtService jwt, AuditService audit, AppProperties props) {
        this.partners = partners;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
        this.props = props;
        this.dummyHash = encoder.encode("timing-equaliser-not-a-real-secret");
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse token(PartnerTokenRequest r) {
        Partner p = partners.findByClientId(r.clientId()).orElse(null);
        Instant now = Instant.now();
        if (p == null || !p.isEnabled()) {
            encoder.matches(r.clientSecret(), dummyHash);
            audit.event(AuditLog.ActorType.ANONYMOUS, null, "PARTNER_LOGIN", "FAILED", "PARTNER", null, "unknown or disabled client");
            throw invalid();
        }
        if (p.isLocked(now)) {
            audit.event(AuditLog.ActorType.PARTNER, p.getId(), "PARTNER_LOGIN", "LOCKED", "PARTNER", p.getId().toString(), null);
            throw invalid();
        }
        if (!encoder.matches(r.clientSecret(), p.getSecretHash())) {
            int attempts = p.getFailedAttempts() + 1;
            if (attempts >= props.security().maxFailedLogins()) {
                p.setLockedUntil(now.plus(props.security().lockDuration()));
                p.setFailedAttempts(0);
            } else {
                p.setFailedAttempts(attempts);
            }
            partners.save(p);
            audit.event(AuditLog.ActorType.PARTNER, p.getId(), "PARTNER_LOGIN", "FAILED", "PARTNER", p.getId().toString(), "bad secret");
            throw invalid();
        }
        p.setFailedAttempts(0);
        partners.save(p);
        audit.event(AuditLog.ActorType.PARTNER, p.getId(), "PARTNER_LOGIN", "SUCCESS", "PARTNER", p.getId().toString(), null);
        return new TokenResponse(jwt.issuePartnerToken(p.getId()), null, "Bearer", props.security().accessTtl().toSeconds());
    }

    private static ApiException invalid() { return ApiException.unauthorized("Credenciais inválidas."); }
}
