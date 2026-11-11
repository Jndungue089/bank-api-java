package com.sb24.api.web;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public half of the signing key, so other services can verify our tokens without sharing any secret. */
@com.sb24.api.access.PublicEndpoint
@RestController
public class JwksController {
    private final Map<String, Object> jwks;

    public JwksController(RSAKey jwk) { this.jwks = new JWKSet(jwk.toPublicJWK()).toJSONObject(); }

    @GetMapping(value = "/.well-known/jwks.json", produces = "application/json")
    public Map<String, Object> keys() { return jwks; }
}
