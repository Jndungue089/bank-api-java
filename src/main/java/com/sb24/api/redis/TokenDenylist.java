package com.sb24.api.redis;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Revoked access-token ids (jti), kept until the token would have expired anyway. Gives immediate logout despite
 * stateless JWTs. If Redis is unreachable we fail OPEN (availability): the token lifetime is only 10 minutes.
 */
@Component
public class TokenDenylist {
    private static final Logger log = LoggerFactory.getLogger(TokenDenylist.class);
    private final RedisOps ops;

    public TokenDenylist(RedisOps ops) { this.ops = ops; }

    public void deny(String jti, Duration ttl) {
        if (jti == null || ttl.isNegative() || ttl.isZero()) return;
        try { ops.set("deny:" + jti, "1", ttl.toMillis()); } catch (RuntimeException e) { log.error("denylist write failed", e); }
    }

    public boolean isDenied(String jti) {
        if (jti == null) return true;
        try { return ops.exists("deny:" + jti); } catch (RuntimeException e) { log.error("denylist read failed, failing open", e); return false; }
    }
}
