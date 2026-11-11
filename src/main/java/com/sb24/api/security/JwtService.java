package com.sb24.api.security;

import com.sb24.api.config.AppProperties;
import com.sb24.api.domain.AppUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    public static final String ISSUER = "sb24-api";
    private final JwtEncoder encoder;
    private final AppProperties props;
    private final String keyId;

    public JwtService(JwtEncoder encoder, AppProperties props, com.sb24.api.crypto.JwtKeyProvider keys) {
        this.keyId = keys.keyId();
        this.encoder = encoder;
        this.props = props;
    }

    public String issueAccessToken(AppUser user) { return issue(user.getId(), "customer"); }

    public String issueAdminToken(UUID adminId) { return issue(adminId, "admin"); }

    public String issuePartnerToken(UUID partnerId) { return issue(partnerId, "partner"); }

    /** The token proves WHO (sub) and WHICH realm. What the subject may do is looked up in the DB on every request. */
    private String issue(UUID subject, String realm) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject.toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.security().accessTtl()))
                .id(UUID.randomUUID().toString())
                .claim("realm", realm)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).type("JWT").build(), claims)).getTokenValue();
    }
}
