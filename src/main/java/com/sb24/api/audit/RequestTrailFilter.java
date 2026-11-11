package com.sb24.api.audit;

import com.sb24.api.domain.AuditLog;
import com.sb24.api.service.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Outermost filter: every API call by anyone leaves a row (customer, admin or anonymous), including calls that are
 * rejected by authentication or rate limiting. Query strings are never stored (they may carry personal data).
 */
public class RequestTrailFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestTrailFilter.class);
    private final AuditService audit;

    public RequestTrailFilter(AuditService audit) { this.audit = audit; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return "OPTIONS".equals(req.getMethod()) || p.startsWith("/actuator") || p.startsWith("/swagger-ui")
                || p.startsWith("/v3/api-docs") || p.startsWith("/.well-known");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            try {
                TrailActor actor = (TrailActor) req.getAttribute(TrailActor.ATTR);
                int status = res.getStatus();
                String outcome = status < 400 ? "SUCCESS" : status == 401 || status == 403 ? "DENIED" : status == 429 || status == 503 ? "THROTTLED" : "FAILED";
                audit.request(actor == null ? AuditLog.ActorType.ANONYMOUS : actor.type(), actor == null ? null : actor.id(),
                        req.getMethod(), req.getRequestURI(), status, outcome, "duration=" + (System.nanoTime() - start) / 1_000_000 + "ms");
            } catch (RuntimeException e) {
                log.error("could not write request trail", e); // the trail must never break the response
            }
        }
    }
}
