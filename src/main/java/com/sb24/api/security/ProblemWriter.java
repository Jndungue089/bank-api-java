package com.sb24.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** RFC 7807 responses for errors raised in servlet filters (outside MVC). */
@Component
public class ProblemWriter {
    private final ObjectMapper mapper;

    public ProblemWriter(ObjectMapper mapper) { this.mapper = mapper; }

    public void write(HttpServletResponse res, HttpStatus status, String code, String detail) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("code", code);
        body.put("detail", detail);
        body.put("requestId", MDC.get(RequestIdFilter.MDC_KEY));
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), body);
    }
}
