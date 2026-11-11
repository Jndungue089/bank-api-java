package com.sb24.api.audit;

/**
 * Who/where of the current request: client IP, self-declared device id (X-Device-Id), User-Agent and request id.
 * Held in a thread-local for the duration of the request so every audit row, wherever it is written, carries it.
 * Background workers have no context, so their rows are attributed to SYSTEM.
 */
public record RequestContext(String ip, String deviceId, String userAgent, String requestId) {
    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    public static RequestContext current() { return CURRENT.get(); }
    static void set(RequestContext c) { CURRENT.set(c); }
    static void clear() { CURRENT.remove(); }
}
