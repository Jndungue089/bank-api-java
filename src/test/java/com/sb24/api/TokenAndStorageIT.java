package com.sb24.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class TokenAndStorageIT extends BaseIT {
    @Autowired JdbcTemplate jdbc;

    private JsonNode header(String jwt) throws Exception {
        return json.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]));
    }

    @Test
    void tokensAreRs256WithKeyIdAndVerifiableViaJwks() throws Exception {
        Client c = registerAndLogin();
        JsonNode h = header(c.access());
        assertEquals("RS256", h.get("alg").asText());
        JsonNode jwks = json.readTree(mvc.perform(get("/.well-known/jwks.json").with(from(newIp()))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode key = jwks.get("keys").get(0);
        assertEquals(h.get("kid").asText(), key.get("kid").asText());
        assertEquals("RSA", key.get("kty").asText());
        assertNull(key.get("d"), "private exponent must never be published");
    }

    @Test
    void forgedAndAlgorithmConfusionTokensAreRejected() throws Exception {
        Client c = registerAndLogin();
        String[] parts = c.access().split("\\.");
        String noneHeader = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String hsHeader = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String[] forged = {noneHeader + "." + parts[1] + ".", hsHeader + "." + parts[1] + "." + parts[2],
                parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 4) + "AAAA"};
        for (String t : forged) {
            mvc.perform(get("/api/v1/accounts").with(from(c.ip())).header("Authorization", "Bearer " + t)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void logoutRevokesTheAccessTokenImmediately() throws Exception {
        Client c = registerAndLogin();
        mvc.perform(get("/api/v1/accounts").with(from(c.ip())).header("Authorization", "Bearer " + c.access())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").with(from(c.ip())).header("Authorization", "Bearer " + c.access())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("refreshToken", c.refresh()))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/accounts").with(from(c.ip())).header("Authorization", "Bearer " + c.access())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").with(from(c.ip())).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("refreshToken", c.refresh())))).andExpect(status().isUnauthorized());
    }

    @Test
    void piiIsEncryptedAtRestButReadableThroughTheApi() throws Exception {
        Client c = registerAndLogin();
        Map<String, Object> row = jdbc.queryForMap("select full_name, phone, national_id, national_id_index, password_hash from app_user where email = ?", c.email());
        assertTrue(row.get("full_name").toString().startsWith("v1:"));
        assertTrue(row.get("phone").toString().startsWith("v1:"));
        assertTrue(row.get("national_id").toString().startsWith("v1:"));
        assertFalse(row.get("full_name").toString().contains("Cliente"));
        assertFalse(row.get("phone").toString().contains("923111222"));
        assertEquals(64, row.get("national_id_index").toString().length());
        assertTrue(row.get("password_hash").toString().startsWith("$argon2id$"), "passwords must be Argon2id hashes");
        JsonNode me = getJson(c.access(), c.ip(), "/api/v1/me");
        assertEquals("Cliente Teste", me.get("fullName").asText());
        assertEquals("923111222", me.get("phone").asText());
    }
}
