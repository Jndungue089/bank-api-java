package com.sb24.api.util;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIp {
    private ClientIp() {}

    /**
     * By default only the socket address is trusted. X-Forwarded-For is honoured only when the API sits behind a
     * proxy we control, and then only the right-most entry (the one our proxy appended) is used, because the
     * left-most ones are client-controlled and trivially spoofed to dodge rate limits.
     */
    public static String resolve(HttpServletRequest request, boolean trustForwardHeaders) {
        if (trustForwardHeaders) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] parts = xff.split(",");
                String last = parts[parts.length - 1].trim();
                if (last.matches("^[0-9a-fA-F:.]{3,45}$")) return last;
            }
        }
        return request.getRemoteAddr();
    }
}
