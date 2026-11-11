package com.sb24.api.web;

import com.sb24.api.config.AppProperties;
import com.sb24.api.service.AuthService;
import com.sb24.api.util.ClientIp;
import com.sb24.api.web.Dtos.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Autenticação")
@com.sb24.api.access.PublicEndpoint
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final AppProperties props;
    private final com.sb24.api.redis.TokenDenylist denylist;

    public AuthController(AuthService auth, AppProperties props, com.sb24.api.redis.TokenDenylist denylist) {
        this.denylist = denylist;
        this.auth = auth;
        this.props = props;
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Registar cliente e abrir conta")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileView register(@Valid @RequestBody RegisterRequest r, HttpServletRequest req) {
        return auth.register(r, ip(req));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Iniciar sessão (RS256 + refresh token)")
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest r, HttpServletRequest req) {
        return auth.login(r, ip(req));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Renovar tokens (rotação com detecção de reutilização)")
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest r, HttpServletRequest req) {
        return auth.refresh(r.refreshToken(), ip(req));
    }

    /** Revokes the refresh-token family and, if a bearer token is supplied, denies its jti until it would expire. */
    @io.swagger.v3.oas.annotations.Operation(summary = "Terminar sessão e revogar tokens")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest r, HttpServletRequest req,
                                       @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt) {
        auth.logout(r.refreshToken(), ip(req));
        if (jwt != null) denylist.deny(jwt.getId(), java.time.Duration.between(java.time.Instant.now(), jwt.getExpiresAt()));
        return ResponseEntity.noContent().build();
    }

    private String ip(HttpServletRequest req) { return ClientIp.resolve(req, props.security().trustForwardHeaders()); }
}
