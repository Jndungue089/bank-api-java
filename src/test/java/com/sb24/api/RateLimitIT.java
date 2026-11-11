package com.sb24.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class RateLimitIT extends BaseIT {

    @Test
    void authEndpointsAreRateLimitedPerIpWithRetryAfter() throws Exception {
        String ip = newIp();
        String body = json.writeValueAsString(Map.of("email", "nobody@example.ao", "password", "Wrong#Passw0rd!!"));
        for (int i = 0; i < 8; i++) {
            mvc.perform(post("/api/v1/auth/login").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        // another client is unaffected
        mvc.perform(post("/api/v1/auth/login").with(from(newIp())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void spoofedForwardedForHeaderDoesNotBypassTheLimit() throws Exception {
        String ip = newIp();
        String body = json.writeValueAsString(Map.of("email", "nobody@example.ao", "password", "Wrong#Passw0rd!!"));
        for (int i = 0; i < 8; i++) {
            mvc.perform(post("/api/v1/auth/login").with(from(ip)).header("X-Forwarded-For", "1.2.3." + i)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").with(from(ip)).header("X-Forwarded-For", "9.9.9.9")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests());
    }

    @Test
    void moneyEndpointsAreLimitedPerUserEvenFromDifferentIps() throws Exception {
        Client c = registerAndLogin();
        String body = json.writeValueAsString(Map.of("fromAccountId", c.accountId(), "providerCode", "UNITEL",
                "reference", "923000000", "amount", new java.math.BigDecimal("10.00")));
        int tooMany = 0;
        for (int i = 0; i < 8; i++) {
            int st = mvc.perform(post("/api/v1/payments").with(from(newIp())).header("Authorization", "Bearer " + c.access())
                    .header("Idempotency-Key", "key-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus();
            if (st == 429) tooMany++;
        }
        assertEquals(2, tooMany, "capacity is 6 per minute: requests 7 and 8 must be refused");
    }
}
