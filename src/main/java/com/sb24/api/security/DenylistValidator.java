package com.sb24.api.security;

import com.sb24.api.redis.TokenDenylist;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Rejects access tokens whose jti was revoked at logout. */
public class DenylistValidator implements OAuth2TokenValidator<Jwt> {
    private final TokenDenylist denylist;

    public DenylistValidator(TokenDenylist denylist) { this.denylist = denylist; }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        return denylist.isDenied(jwt.getId())
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "token revoked", null))
                : OAuth2TokenValidatorResult.success();
    }
}
