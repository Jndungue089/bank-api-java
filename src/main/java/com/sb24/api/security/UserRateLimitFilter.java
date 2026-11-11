package com.sb24.api.security;

import com.sb24.api.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stage 2, after the JWT is validated: per-user limits keyed by the token subject (so rotating IPs does not help),
 * with a tighter bucket for money movement. Also applies soft throttling: when a user is close to exhausting the
 * money bucket the response is deliberately slowed down instead of rejected outright.
 */
public class UserRateLimitFilter extends OncePerRequestFilter {
    private final AppProperties props;
    private final RateLimiterService limiter;
    private final ProblemWriter problems;

    public UserRateLimitFilter(AppProperties props, RateLimiterService limiter, ProblemWriter problems) {
        this.props = props;
        this.limiter = limiter;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken) { // tell the outer trail filter who this is
            String realm = String.valueOf(((JwtAuthenticationToken) auth).getToken().getClaimAsString("realm"));
            req.setAttribute(com.sb24.api.audit.TrailActor.ATTR, new com.sb24.api.audit.TrailActor(
                    "admin".equals(realm) ? com.sb24.api.domain.AuditLog.ActorType.ADMIN
                            : "partner".equals(realm) ? com.sb24.api.domain.AuditLog.ActorType.PARTNER : com.sb24.api.domain.AuditLog.ActorType.CUSTOMER,
                    java.util.UUID.fromString(auth.getName())));
        }
        if (!props.rateLimit().enabled() || !(auth instanceof JwtAuthenticationToken)) { chain.doFilter(req, res); return; }
        String user = auth.getName();
        boolean money = RateLimitSupport.isMoneyMovement(req.getMethod(), req.getRequestURI());
        AppProperties.Limit limit = money ? props.rateLimit().money() : props.rateLimit().general();
        if (!RateLimitSupport.check(limiter, problems, res, (money ? "money:" : "user:") + user, limit)) return;

        if (money) {
            long remaining = Long.parseLong(res.getHeader("X-RateLimit-Remaining"));
            long threshold = (long) limit.capacity() * props.rateLimit().softThrottlePercent() / 100;
            long delay = props.rateLimit().softThrottleDelayMs();
            if (remaining <= threshold && delay > 0) {
                res.setHeader("X-Throttle-Delay-Ms", String.valueOf(delay));
                try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        chain.doFilter(req, res);
    }
}
