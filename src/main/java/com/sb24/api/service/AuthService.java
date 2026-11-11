package com.sb24.api.service;

import com.sb24.api.config.AppProperties;
import com.sb24.api.crypto.FieldCipher;
import com.sb24.api.domain.AppUser;
import com.sb24.api.domain.Role;
import com.sb24.api.domain.RefreshToken;
import com.sb24.api.repo.RefreshTokenRepository;
import com.sb24.api.repo.UserRepository;
import com.sb24.api.security.JwtService;
import com.sb24.api.util.Hashing;
import com.sb24.api.web.Dtos.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AccountService accounts;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final AppProperties props;
    private final FieldCipher cipher;
    private final DeviceService devices;
    private final RbacService rbac;
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, AccountService accounts,
                       PasswordEncoder encoder, JwtService jwt, AuditService audit, AppProperties props, FieldCipher cipher, DeviceService devices, RbacService rbac) {
        this.rbac = rbac;
        this.devices = devices;
        this.cipher = cipher;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.accounts = accounts;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
        this.props = props;
        this.dummyHash = encoder.encode("timing-equaliser-not-a-real-password");
    }

    @Transactional(noRollbackFor = ApiException.class)
    public ProfileView register(RegisterRequest r, String ip) {
        validatePasswordPolicy(r.password(), r.email());
        String email = r.email().trim().toLowerCase();
        if (users.existsByEmailOrNationalIdIndex(email, cipher.blindIndex(r.nationalId()))) {
            audit.record(null, "REGISTER", "DENIED", ip, "duplicate identity");
            throw ApiException.conflict("REGISTRATION_FAILED", "Não foi possível concluir o registo com os dados fornecidos.");
        }
        AppUser u = createUser(r.fullName().trim(), email, r.phone(), r.nationalId(), r.password(), Role.CUSTOMER);
        accounts.openCurrentAccount(u.getId());
        audit.record(u.getId(), "REGISTER", "SUCCESS", ip, null);
        return accounts.profile(u.getId());
    }

    /** PII is encrypted by the entity converters; the national id also gets an HMAC blind index for uniqueness. */
    @Transactional
    public AppUser createUser(String fullName, String email, String phone, String nationalId, String password, Role role) {
        AppUser u = new AppUser();
        u.setFullName(fullName);
        u.setEmail(email);
        u.setPhone(phone);
        u.setNationalId(nationalId);
        u.setNationalIdIndex(cipher.blindIndex(nationalId));
        u.setPasswordHash(encoder.encode(password));
        u.setRole(role);
        users.save(u);
        if (role == Role.CUSTOMER) rbac.grant(u.getId(), com.sb24.api.access.Rbac.CUSTOMER, null);
        return u;
    }

    /** noRollbackFor: failed-attempt counters and lockouts must be persisted even though we throw. */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse login(LoginRequest r, String ip) {
        String email = r.email().trim().toLowerCase();
        AppUser user = users.findByEmail(email).orElse(null);
        Instant now = Instant.now();

        if (user == null || !user.isEnabled() || user.getRole() == Role.SYSTEM) {
            encoder.matches(r.password(), dummyHash); // equalise timing so unknown accounts are not distinguishable
            audit.record(null, "LOGIN", "FAILED", ip, "unknown or disabled");
            throw invalidCredentials();
        }
        if (user.isLocked(now)) {
            audit.record(user.getId(), "LOGIN", "LOCKED", ip, "attempt while locked");
            throw invalidCredentials(); // same answer as a wrong password: do not confirm the account exists or is locked
        }
        if (!encoder.matches(r.password(), user.getPasswordHash())) {
            int attempts = user.getFailedAttempts() + 1;
            if (attempts >= props.security().maxFailedLogins()) {
                user.setLockedUntil(now.plus(props.security().lockDuration()));
                user.setFailedAttempts(0);
                audit.record(user.getId(), "ACCOUNT_LOCK", "SUCCESS", ip, "too many failed logins");
            } else {
                user.setFailedAttempts(attempts);
            }
            users.save(user);
            audit.record(user.getId(), "LOGIN", "FAILED", ip, "bad password");
            throw invalidCredentials();
        }
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        users.save(user);
        audit.customer(user.getId(), "LOGIN", "SUCCESS", "USER", user.getId().toString(), null);
        devices.recordLogin(user.getId());
        return issue(user, UUID.randomUUID());
    }

    /** Refresh-token rotation with reuse detection: replaying an already-used token revokes the whole family. */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(String rawToken, String ip) {
        RefreshToken t = refreshTokens.findByTokenHash(Hashing.sha256Hex(rawToken)).orElseThrow(AuthService::invalidToken);
        if (t.isUsed()) {
            refreshTokens.revokeFamily(t.getFamilyId());
            audit.record(t.getUserId(), "REFRESH_REUSE", "DENIED", ip, "token replay, family revoked");
            throw invalidToken();
        }
        if (t.isRevoked() || t.getExpiresAt().isBefore(Instant.now())) throw invalidToken();
        AppUser user = users.findById(t.getUserId()).filter(AppUser::isEnabled).orElseThrow(AuthService::invalidToken);
        t.setUsed(true);
        refreshTokens.save(t);
        audit.record(user.getId(), "REFRESH", "SUCCESS", ip, null);
        return issue(user, t.getFamilyId());
    }

    @Transactional
    public void logout(String rawToken, String ip) {
        refreshTokens.findByTokenHash(Hashing.sha256Hex(rawToken)).ifPresent(t -> {
            refreshTokens.revokeFamily(t.getFamilyId());
            audit.record(t.getUserId(), "LOGOUT", "SUCCESS", ip, null);
        });
    }

    private TokenResponse issue(AppUser user, UUID family) {
        byte[] bytes = new byte[48];
        RNG.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshToken rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setFamilyId(family);
        rt.setTokenHash(Hashing.sha256Hex(raw)); // only the hash is stored: a DB leak does not leak usable tokens
        rt.setExpiresAt(Instant.now().plus(props.security().refreshTtl()));
        refreshTokens.save(rt);
        return new TokenResponse(jwt.issueAccessToken(user), raw, "Bearer", props.security().accessTtl().toSeconds());
    }

    private void validatePasswordPolicy(String pw, String email) {
        boolean ok = pw.length() >= 12
                && pw.length() <= 128
                && pw.chars().anyMatch(Character::isUpperCase)
                && pw.chars().anyMatch(Character::isLowerCase)
                && pw.chars().anyMatch(Character::isDigit)
                && pw.chars().anyMatch(c -> !Character.isLetterOrDigit(c))
                && !pw.toLowerCase().contains(email.toLowerCase().split("@")[0]);
        if (!ok) {
            throw ApiException.badRequest("WEAK_PASSWORD",
                    "A palavra-passe deve ter 12 a 128 caracteres, com maiúsculas, minúsculas, dígitos e símbolos, e não conter o seu email.");
        }
    }

    private static ApiException invalidCredentials() { return ApiException.unauthorized("Credenciais inválidas."); }
    private static ApiException invalidToken() { return ApiException.unauthorized("Token inválido ou expirado."); }
}
