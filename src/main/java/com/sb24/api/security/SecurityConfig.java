package com.sb24.api.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sb24.api.crypto.JwtKeyProvider;
import com.sb24.api.redis.TokenDenylist;
import com.sb24.api.config.AppProperties;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    RSAKey jwk(JwtKeyProvider keys) {
        return new RSAKey.Builder(keys.publicKey()).privateKey(keys.privateKey()).keyID(keys.keyId())
                .algorithm(JWSAlgorithm.RS256).keyUse(KeyUse.SIGNATURE).build();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey jwk) { return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk))); }

    @Bean
    JwtDecoder jwtDecoder(JwtKeyProvider keys, TokenDenylist denylist) throws Exception {
        // Algorithm pinned to RS256 (no "alg: none", no HS256-with-public-key confusion). Enforces exp, nbf, iss and revocation.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey()).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(JwtService.ISSUER), new DenylistValidator(denylist)));
        return decoder;
    }

    /** Argon2id (memory-hard key stretching), parameters at the OWASP minimum: 19 MiB, 2 iterations, 1 lane. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AppProperties props, RateLimiterService limiter,
                                    ProblemWriter problems, com.sb24.api.service.AuditService audit) throws Exception {
        AuthenticationEntryPoint entryPoint = (req, res, ex) -> {
            res.setHeader("WWW-Authenticate", "Bearer");
            problems.write(res, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Autenticação necessária ou token inválido.");
        };
        AccessDeniedHandler denied = (req, res, ex) ->
                problems.write(res, HttpStatus.FORBIDDEN, "FORBIDDEN", "Sem permissão para este recurso.");

        // Spring Security only learns the REALM from the token. Permissions come from the DB (PermissionInterceptor / PolicyService).
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                "REALM_" + String.valueOf(jwt.getClaimAsString("realm")).toUpperCase())));

        boolean docs = props.security().docsEnabled();

        http
            .csrf(AbstractHttpConfigurer::disable) // stateless bearer-token API, no cookies, so no CSRF surface
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                    .authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
            .headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives(docs
                        ? "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'"
                        : "default-src 'none'; frame-ancestors 'none'"))
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .httpStrictTransportSecurity(s -> s.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
            .authorizeHttpRequests(a -> {
                a.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                a.requestMatchers("/api/v1/auth/**", "/api/v1/admin/auth/login", "/api/v1/partner/auth/token", "/.well-known/jwks.json").permitAll();
                a.requestMatchers("/actuator/health/**").permitAll();
                if (docs) a.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                // Coarse gate by realm; the fine-grained decision (permission + ABAC) is made per handler from the DB.
                a.requestMatchers("/api/v1/admin/**").hasAuthority("REALM_ADMIN");
                a.requestMatchers("/api/v1/partner/**").hasAuthority("REALM_PARTNER");
                a.requestMatchers("/api/v1/me", "/api/v1/accounts/**", "/api/v1/transfers/**", "/api/v1/payments/**", "/api/v1/notifications/**",
                        "/api/v1/beneficiaries/**", "/api/v1/savings/**", "/api/v1/direct-debits/**").hasAuthority("REALM_CUSTOMER");
                a.anyRequest().denyAll();
            })
            .addFilterBefore(new RequestIdFilter(), SecurityContextHolderFilter.class)
            .addFilterAfter(new com.sb24.api.audit.RequestContextFilter(props), RequestIdFilter.class)
            .addFilterAfter(new com.sb24.api.audit.RequestTrailFilter(audit), com.sb24.api.audit.RequestContextFilter.class)
            .addFilterBefore(new IpRateLimitFilter(props, limiter, problems), BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new UserRateLimitFilter(props, limiter, problems), BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new ConcurrencyThrottleFilter(props, problems), UserRateLimitFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration c = new CorsConfiguration(); // no origins configured => all cross-origin requests denied
        c.setAllowedOrigins(props.security().allowedOrigins());
        c.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id"));
        c.setExposedHeaders(List.of("X-Request-Id", "X-RateLimit-Limit", "X-RateLimit-Remaining", "Retry-After", "Idempotent-Replayed"));
        c.setAllowCredentials(false);
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }
}
