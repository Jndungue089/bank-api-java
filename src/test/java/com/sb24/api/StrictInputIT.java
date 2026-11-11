package com.sb24.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Strict field validation, exact decimals and XSS/injection rejection at the API edge. */
class StrictInputIT extends BaseIT {

    private ResultActions transfer(Client c, String rawJson) throws Exception {
        return mvc.perform(post("/api/v1/transfers").with(from(c.ip())).header("Authorization", "Bearer " + c.access())
                .header("Idempotency-Key", "idem-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(rawJson));
    }

    private String body(Client c, String amountJson, String extra) {
        return "{\"fromAccountId\":\"" + c.accountId() + "\",\"toIban\":\"" + c.iban() + "\",\"amount\":" + amountJson + extra + "}";
    }

    @Test
    void amountsMustBeExactJsonNumbersWithAtMostTwoDecimals() throws Exception {
        Client c = registerAndLogin();
        transfer(c, body(c, "10.001", "")).andExpect(status().isBadRequest());     // 3 decimals
        transfer(c, body(c, "\"10.00\"", "")).andExpect(status().isBadRequest());  // string, not number
        transfer(c, body(c, "-5.00", "")).andExpect(status().isBadRequest());      // negative
        transfer(c, body(c, "0", "")).andExpect(status().isBadRequest());          // zero
        transfer(c, body(c, "1e400", "")).andExpect(status().isBadRequest());      // absurd magnitude
        transfer(c, body(c, "null", "")).andExpect(status().isBadRequest());
    }

    @Test
    void unknownAndDuplicateFieldsAreRejected() throws Exception {
        Client c = registerAndLogin();
        transfer(c, body(c, "5.00", ",\"role\":\"ADMIN\"")).andExpect(status().isBadRequest());
        transfer(c, body(c, "5.00", ",\"amount\":5000.00")).andExpect(status().isBadRequest()); // duplicate key
        transfer(c, "{not json").andExpect(status().isBadRequest());
        transfer(c, body(c, "5.00", "") + " trailing").andExpect(status().isBadRequest());
    }

    @Test
    void markupAndControlCharactersAreRejectedInFreeText() throws Exception {
        Client c = registerAndLogin();
        for (String evil : new String[] {"<script>alert(1)</script>", "\\\" onmouseover=\\\"x", "${jndi:ldap://x}", "a\\u0000b", "'; DROP TABLE account;--<"}) {
            transfer(c, body(c, "5.00", ",\"description\":\"" + evil + "\"")).andExpect(status().isBadRequest());
        }
    }

    @Test
    void registrationRejectsMarkupInNames() throws Exception {
        String ip = newIp();
        String evil = "{\"fullName\":\"<img src=x onerror=alert(1)>\",\"email\":\"x@example.ao\",\"phone\":\"923111222\","
                + "\"nationalId\":\"123456789LA001\",\"password\":\"" + PW + "\"}";
        mvc.perform(post("/api/v1/auth/register").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(evil))
                .andExpect(status().isBadRequest());
    }

    @Test
    void paginationParametersAreBounded() throws Exception {
        Client c = registerAndLogin();
        mvc.perform(get("/api/v1/accounts/" + c.accountId() + "/transactions?size=100000").with(from(c.ip()))
                .header("Authorization", "Bearer " + c.access())).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
        mvc.perform(get("/api/v1/accounts/not-a-uuid").with(from(c.ip())).header("Authorization", "Bearer " + c.access()))
                .andExpect(status().isBadRequest());
    }
}
