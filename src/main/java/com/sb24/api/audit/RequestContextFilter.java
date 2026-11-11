package com.sb24.api.audit;

import com.sb24.api.config.AppProperties;
import com.sb24.api.security.RequestIdFilter;
import com.sb24.api.util.ClientIp;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestContextFilter extends OncePerRequestFilter {
    private static final Pattern DEVICE = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");
    private final AppProperties props;

    public RequestContextFilter(AppProperties props) { this.props = props; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String device = req.getHeader("X-Device-Id");
        if (device != null && !DEVICE.matcher(device).matches()) device = null; // untrusted input: only well-formed ids are kept
        RequestContext.set(new RequestContext(ClientIp.resolve(req, props.security().trustForwardHeaders()), device,
                clean(req.getHeader("User-Agent"), 255), MDC.get(RequestIdFilter.MDC_KEY)));
        try {
            chain.doFilter(req, res);
        } finally {
            RequestContext.clear();
        }
    }

    /** Strips control characters (log/header injection) and truncates. */
    static String clean(String v, int max) {
        if (v == null) return null;
        String s = v.replaceAll("[\\p{Cntrl}]", "");
        return s.length() > max ? s.substring(0, max) : s;
    }
}
