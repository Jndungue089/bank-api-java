package com.sb24.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class BaseIT {
    static final String PW = "Str0ng#Passw0rd!x";
    private static final AtomicInteger IP = new AtomicInteger(1);
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired protected com.sb24.api.service.AdminService adminSvc;
    @Autowired protected com.sb24.api.queue.QueueWorker queueWorker;

    protected record Admin(String ip, UUID id, String token, String email) {}

    protected static final String ADMIN_PW = "Adm1n#Passw0rd-2026";

    protected Admin newAdmin(com.sb24.api.domain.AdminRole role) throws Exception {
        String ip = newIp();
        String email = "adm" + UUID.randomUUID().toString().substring(0, 8) + "@example.ao";
        var a = adminSvc.newAdmin("Admin Teste", email, ADMIN_PW, role, null);
        String res = mvc.perform(post("/api/v1/admin/auth/login").with(from(ip)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", email, "password", ADMIN_PW))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Admin(ip, a.getId(), json.readTree(res).get("accessToken").asText(), email);
    }

    protected static UUID subject(String jwt) throws Exception {
        return UUID.fromString(new ObjectMapper().readTree(java.util.Base64.getUrlDecoder().decode(jwt.split("\\.")[1])).get("sub").asText());
    }

    /** Each logical client gets its own IP so rate-limit buckets of different tests never interfere. */
    protected static String newIp() { return "10.1." + (IP.get() / 250) + "." + (IP.getAndIncrement() % 250 + 1); }

    protected static RequestPostProcessor from(String ip) { return r -> { r.setRemoteAddr(ip); return r; }; }

    protected record Client(String ip, String email, String access, String refresh, UUID accountId, String iban) {}

    protected Client registerAndLogin() throws Exception {
        String ip = newIp();
        String email = "u" + UUID.randomUUID().toString().substring(0, 8) + "@example.ao";
        String nid = String.format("%09d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L)) + "LA001";
        String body = json.writeValueAsString(java.util.Map.of("fullName", "Cliente Teste", "email", email,
                "phone", "923111222", "nationalId", nid, "password", PW));
        mvc.perform(post("/api/v1/auth/register").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        JsonNode tokens = login(ip, email, PW);
        JsonNode acc = getJson(tokens.get("accessToken").asText(), ip, "/api/v1/accounts").get(0);
        return new Client(ip, email, tokens.get("accessToken").asText(), tokens.get("refreshToken").asText(),
                UUID.fromString(acc.get("id").asText()), acc.get("iban").asText());
    }

    protected JsonNode login(String ip, String email, String pw) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("email", email, "password", pw));
        String res = mvc.perform(post("/api/v1/auth/login").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    protected JsonNode getJson(String token, String ip, String path) throws Exception {
        String res = mvc.perform(get(path).with(from(ip)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }
}
