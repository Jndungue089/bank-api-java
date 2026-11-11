package com.sb24.api.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Deny-by-default gate in front of every /api/v1 handler: no @RequiresPermission and no @PublicEndpoint = refused. */
@Component
public class PermissionInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(PermissionInterceptor.class);
    private final PolicyService policy;

    public PermissionInterceptor(PolicyService policy) { this.policy = policy; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (!(handler instanceof HandlerMethod hm) || !req.getRequestURI().startsWith("/api/v1/")) return true;
        if (isPublic(hm)) return true;

        RequiresPermission rp = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequiresPermission.class);
        if (rp == null) rp = AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RequiresPermission.class);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Subject subject = auth instanceof JwtAuthenticationToken t ? Subject.of(t.getToken()) : new Subject(new UUID(0, 0), "none");
        if (rp == null) {
            log.error("FAIL-CLOSED: handler {} has no @RequiresPermission or @PublicEndpoint", hm.getShortLogMessage());
            throw new PolicyDeniedException(subject, "NONE", "FailClosed", "NO_POLICY_DEFINED", Resource.none(), false);
        }
        policy.requirePermission(subject, rp.value());
        req.setAttribute("sb24.permission", rp.value());
        return true;
    }

    private static boolean isPublic(HandlerMethod hm) {
        return AnnotatedElementUtils.hasAnnotation(hm.getMethod(), PublicEndpoint.class) || AnnotatedElementUtils.hasAnnotation(hm.getBeanType(), PublicEndpoint.class);
    }
}
