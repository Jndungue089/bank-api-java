package com.sb24.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class SecurityIT extends BaseIT {

    @Test
    void protectedEndpointsRequireToken() throws Exception {
        mvc.perform(get("/api/v1/accounts").with(from(newIp()))).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(get("/api/v1/accounts").with(from(newIp())).header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/api/v1/accounts").with(from(newIp())))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void customerCannotReachAdminEndpoints() throws Exception {
        Client c = registerAndLogin();
        mvc.perform(get("/api/v1/admin/audit").with(from(c.ip())).header("Authorization", "Bearer " + c.access()))
                .andExpect(status().isForbidden());
    }

    @Test
    void weakPasswordIsRejectedAndNotEchoed() throws Exception {
        String body = json.writeValueAsString(Map.of("fullName", "Cliente Teste", "email", "weak@example.ao",
                "phone", "923111222", "nationalId", "123456789LA001", "password", "passwordpassword"));
        String res = mvc.perform(post("/api/v1/auth/register").with(from(newIp())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertFalse(res.contains("passwordpassword"));
    }

    @Test
    void accountLocksAfterRepeatedFailuresAndAnswersGenerically() throws Exception {
        Client c = registerAndLogin();
        String ip = newIp();
        String bad = json.writeValueAsString(Map.of("email", c.email(), "password", "Wrong#Passw0rd!!"));
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/auth/login").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isUnauthorized());
        }
        // correct password is now refused too (locked), with the same generic answer
        String good = json.writeValueAsString(Map.of("email", c.email(), "password", PW));
        String res = mvc.perform(post("/api/v1/auth/login").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(res.contains("Credenciais inválidas"));
    }

    @Test
    void refreshTokenRotatesAndReuseRevokesTheFamily() throws Exception {
        Client c = registerAndLogin();
        String ip = newIp();
        String r1 = json.writeValueAsString(Map.of("refreshToken", c.refresh()));
        String second = mvc.perform(post("/api/v1/auth/refresh").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(r1))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String newRefresh = json.readTree(second).get("refreshToken").asText();

        // replaying the OLD token = theft signal: denied, and the NEW token is revoked as well
        mvc.perform(post("/api/v1/auth/refresh").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(r1))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").with(from(ip)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", newRefresh)))).andExpect(status().isUnauthorized());
    }
}
