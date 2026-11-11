package com.sb24.api.security;

import com.sb24.api.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Throttling by concurrency (bulkhead) for money movement:
 *  - per user: at most N in-flight transfers/payments (stops parallel double-spend attempts, 429);
 *  - global: at most M in-flight money operations for the whole instance, protecting the DB (503, load shedding).
 */
public class ConcurrencyThrottleFilter extends OncePerRequestFilter {
    private final AppProperties props;
    private final ProblemWriter problems;
    private final ConcurrentHashMap<String, Integer> perUser = new ConcurrentHashMap<>();
    private final AtomicInteger global = new AtomicInteger();

    public ConcurrencyThrottleFilter(AppProperties props, ProblemWriter problems) {
        this.props = props;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!RateLimitSupport.isMoneyMovement(req.getMethod(), req.getRequestURI()) || !(auth instanceof JwtAuthenticationToken)) {
            chain.doFilter(req, res);
            return;
        }
        String user = auth.getName();
        if (!enterUser(user)) {
            res.setHeader("Retry-After", "1");
            problems.write(res, HttpStatus.TOO_MANY_REQUESTS, "CONCURRENCY_LIMIT",
                    "Já existe uma operação financeira em curso. Aguarde que termine.");
            return;
        }
        try {
            if (global.incrementAndGet() > props.banking().maxGlobalMoneyConcurrency()) {
                res.setHeader("Retry-After", "2");
                problems.write(res, HttpStatus.SERVICE_UNAVAILABLE, "OVERLOADED", "Serviço temporariamente sobrecarregado.");
                return;
            }
            chain.doFilter(req, res);
        } finally {
            global.decrementAndGet();
            leaveUser(user);
        }
    }

    private boolean enterUser(String user) {
        boolean[] entered = {false};
        perUser.compute(user, (k, v) -> {
            int c = v == null ? 0 : v;
            if (c < props.banking().maxConcurrentPerUser()) { entered[0] = true; return c + 1; }
            return v;
        });
        return entered[0];
    }

    private void leaveUser(String user) {
        perUser.compute(user, (k, v) -> v == null || v <= 1 ? null : v - 1);
    }
}
