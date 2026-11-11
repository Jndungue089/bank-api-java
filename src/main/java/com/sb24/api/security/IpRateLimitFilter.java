package com.sb24.api.security;

import com.sb24.api.config.AppProperties;
import com.sb24.api.util.ClientIp;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stage 1, before authentication: a per-IP safety net for everything, plus a much stricter bucket for the
 * unauthenticated credential endpoints (login, register, refresh), which blunts credential stuffing and brute force.
 */
public class IpRateLimitFilter extends OncePerRequestFilter {
    private final AppProperties props;
    private final RateLimiterService limiter;
    private final ProblemWriter problems;

    public IpRateLimitFilter(AppProperties props, RateLimiterService limiter, ProblemWriter problems) {
        this.props = props;
        this.limiter = limiter;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!props.rateLimit().enabled()) { chain.doFilter(req, res); return; }
        String ip = ClientIp.resolve(req, props.security().trustForwardHeaders());
        String path = req.getRequestURI();
        if ((path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/admin/auth/") || path.startsWith("/api/v1/partner/auth/"))
                && !RateLimitSupport.check(limiter, problems, res, "auth:" + ip, props.rateLimit().auth())) return;
        if (!RateLimitSupport.check(limiter, problems, res, "ip:" + ip, props.rateLimit().ipGlobal())) return;
        chain.doFilter(req, res);
    }
}
