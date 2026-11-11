package com.sb24.api.security;

import com.sb24.api.config.AppProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;

final class RateLimitSupport {
    private RateLimitSupport() {}

    /** @return true if the request may proceed. Writes a 429 with Retry-After otherwise. */
    static boolean check(RateLimiterService limiter, ProblemWriter problems, HttpServletResponse res,
                         String key, AppProperties.Limit limit) throws IOException {
        RateLimiterService.Decision probe = limiter.consume(key, limit);
        res.setHeader("X-RateLimit-Limit", String.valueOf(limit.capacity()));
        res.setHeader("X-RateLimit-Remaining", String.valueOf(probe.remaining()));
        if (probe.allowed()) return true;
        long retry = Math.max(1, (long) Math.ceil(probe.retryAfterMillis() / 1000.0));
        res.setHeader("Retry-After", String.valueOf(retry));
        problems.write(res, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Demasiados pedidos. Tente novamente dentro de " + retry + " segundos.");
        return false;
    }

    static boolean isMoneyMovement(String method, String path) {
        return "POST".equals(method) && (path.equals("/api/v1/transfers") || path.equals("/api/v1/payments") || path.startsWith("/api/v1/savings"));
    }
}
