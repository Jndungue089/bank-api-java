package com.sb24.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sb24.api.domain.AdminRole;
import com.sb24.api.service.AccountService;
import com.sb24.api.service.AdminService;
import com.sb24.api.service.LedgerIntegrityService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class DirectDebitIT extends BaseIT {
    @Autowired AccountService accounts;
    @Autowired AdminService adminService;
    @Autowired LedgerIntegrityService integrity;

    record Creditor(String ip, String token, UUID id) {}

    /** Reference numbers must be unique per creditor, so each test uses its own. */
    private static String ref() { return String.format("%010d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 10_000_000_000L)); }

    private Creditor creditor(String code) throws Exception {
        String clientId = code.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8), secret = "Cred#Secret-2026-" + UUID.randomUUID();
        var p = adminService.newPartner(code, "Credor " + code, clientId, secret, null);
        String ip = newIp();
        String res = mvc.perform(post("/api/v1/partner/auth/token").with(from(ip)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("clientId", clientId, "clientSecret", secret)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Creditor(ip, json.readTree(res).get("accessToken").asText(), p.getId());
    }

    private JsonNode mandate(Client c, String code, String reference, String max, String freq, int expected) throws Exception {
        String res = mvc.perform(post("/api/v1/direct-debits").with(from(c.ip())).header("Authorization", "Bearer " + c.access()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("accountId", c.accountId(), "creditorCode", code, "creditorReference", reference, "maxAmount", new BigDecimal(max), "frequency", freq))))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    private JsonNode collect(Creditor cr, String reference, String externalRef, String amount, LocalDate due, int expected) throws Exception {
        String res = mvc.perform(post("/api/v1/partner/collections").with(from(cr.ip())).header("Authorization", "Bearer " + cr.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("creditorReference", reference, "externalRef", externalRef, "amount", new BigDecimal(amount), "dueDate", due.toString()))))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    @Test
    void fullCycleMandateThenCollectionIsPaidAsynchronously() throws Exception {
        Client c = registerAndLogin();
        accounts.cashIn(c.accountId(), new BigDecimal("30000.00"), "test");
        String ref = ref();
        JsonNode m = mandate(c, "EPAL", ref, "8000.00", "MONTHLY", 201);
        Creditor epal = creditor("EPAL");
        LocalDate due = LocalDate.now();

        JsonNode col = collect(epal, ref, "inv-" + UUID.randomUUID().toString().substring(0, 8), "5000.00", due, 202);
        assertEquals("PENDING", col.get("status").asText());
        assertEquals(0, new BigDecimal("30000.00").compareTo(getJson(c.access(), c.ip(), "/api/v1/accounts/" + c.accountId()).get("balance").decimalValue())); // nothing yet: asynchronous
        queueWorker.drain();

        JsonNode done = json.readTree(mvc.perform(get("/api/v1/partner/collections/" + col.get("id").asText()).with(from(epal.ip())).header("Authorization", "Bearer " + epal.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("PAID", done.get("status").asText());
        assertEquals(0, new BigDecimal("25000.00").compareTo(getJson(c.access(), c.ip(), "/api/v1/accounts/" + c.accountId()).get("balance").decimalValue()));
        assertEquals(1, getJson(c.access(), c.ip(), "/api/v1/direct-debits/" + m.get("id").asText() + "/collections").size());
        assertTrue(getJson(c.access(), c.ip(), "/api/v1/notifications").toString().contains("pago"));
        assertTrue(integrity.verify().ok(), () -> integrity.verify().violations().toString());
        assertTrue(db.queryForObject("select count(*) from audit_log where action = 'COLLECTION_SUBMIT' and actor_type = 'PARTNER' and actor_id = ?", Integer.class, epal.id()) == 1);
    }

    @Test
    void mandateRulesAreEnforcedForEveryCollection() throws Exception {
        Client c = registerAndLogin();
        accounts.cashIn(c.accountId(), new BigDecimal("30000.00"), "test");
        String ref = ref();
        mandate(c, "EPAL", ref, "8000.00", "MONTHLY", 201);
        Creditor epal = creditor("EPAL");
        LocalDate due = LocalDate.now();

        JsonNode over = collect(epal, ref, "big-" + UUID.randomUUID().toString().substring(0, 8), "9000.00", due, 403);        // above the mandate ceiling (ABAC)
        assertEquals("EXCEEDS_MANDATE_LIMIT", over.get("code").asText());
        collect(epal, ref, "old-" + UUID.randomUUID().toString().substring(0, 8), "100.00", due.minusDays(30), 422);          // due date out of window
        collect(epal, ref(), "x-" + UUID.randomUUID().toString().substring(0, 8), "100.00", due, 404);                         // no such mandate for this creditor

        String first = "e-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode a = collect(epal, ref, first, "4000.00", due, 202);
        JsonNode again = collect(epal, ref, first, "4000.00", due, 202);                                                         // idempotent retry
        assertEquals(a.get("id").asText(), again.get("id").asText());
        collect(epal, ref, first, "4100.00", due, 409);                                                                          // same externalRef, different data
        queueWorker.drain();
        // second collection in the same month: rejected by the frequency rule
        JsonNode second = collect(epal, ref, "e2-" + UUID.randomUUID().toString().substring(0, 8), "1000.00", due, 202);
        queueWorker.drain();
        JsonNode st = json.readTree(mvc.perform(get("/api/v1/partner/collections/" + second.get("id").asText()).with(from(epal.ip())).header("Authorization", "Bearer " + epal.token()))
                .andReturn().getResponse().getContentAsString());
        assertEquals("REJECTED", st.get("status").asText());
        assertEquals("PERIOD_ALREADY_COLLECTED", st.get("reason").asText());
        assertEquals(0, new BigDecimal("26000.00").compareTo(getJson(c.access(), c.ip(), "/api/v1/accounts/" + c.accountId()).get("balance").decimalValue()));
    }

    @Test
    void returnedWhenThereAreNoFundsOrTheMandateIsNotActive() throws Exception {
        Client c = registerAndLogin();
        accounts.cashIn(c.accountId(), new BigDecimal("1000.00"), "test");
        String ref = ref();
        JsonNode m = mandate(c, "ENDE", ref, "5000.00", "WEEKLY", 201);
        Creditor ende = creditor("ENDE");
        JsonNode poor = collect(ende, ref, "p-" + UUID.randomUUID().toString().substring(0, 8), "4000.00", LocalDate.now(), 202);
        queueWorker.drain();
        assertEquals("RETURNED", statusOf(ende, poor.get("id").asText()).get("status").asText());
        assertEquals("INSUFFICIENT_FUNDS", statusOf(ende, poor.get("id").asText()).get("reason").asText());

        mvc.perform(post("/api/v1/direct-debits/" + m.get("id").asText() + "/pause").with(from(c.ip())).header("Authorization", "Bearer " + c.access())).andExpect(status().isOk());
        JsonNode paused = collect(ende, ref, "q-" + UUID.randomUUID().toString().substring(0, 8), "100.00", LocalDate.now(), 202);
        queueWorker.drain();
        assertEquals("MANDATE_PAUSED", statusOf(ende, paused.get("id").asText()).get("reason").asText());
        mvc.perform(post("/api/v1/direct-debits/" + m.get("id").asText() + "/cancel").with(from(c.ip())).header("Authorization", "Bearer " + c.access())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/direct-debits/" + m.get("id").asText() + "/resume").with(from(c.ip())).header("Authorization", "Bearer " + c.access())).andExpect(status().isUnprocessableEntity()); // cancelled is final
    }

    @Test
    void creditorsAreScopedToTheirOwnMandatesAndCollections() throws Exception {
        Client c = registerAndLogin(), other = registerAndLogin();
        accounts.cashIn(c.accountId(), new BigDecimal("10000.00"), "test");
        String ref = ref();
        JsonNode m = mandate(c, "EPAL", ref, "5000.00", "MONTHLY", 201);
        Creditor epal = creditor("EPAL"), ende = creditor("ENDE"), epal2 = creditor("EPAL");
        collect(ende, ref, "z-" + UUID.randomUUID().toString().substring(0, 8), "100.00", LocalDate.now(), 404);                // ENDE cannot touch an EPAL mandate
        JsonNode mine = collect(epal, ref, "ok-" + UUID.randomUUID().toString().substring(0, 8), "100.00", LocalDate.now(), 202);
        mvc.perform(get("/api/v1/partner/collections/" + mine.get("id").asText()).with(from(ende.ip())).header("Authorization", "Bearer " + ende.token())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/partner/collections/" + mine.get("id").asText()).with(from(epal2.ip())).header("Authorization", "Bearer " + epal2.token())).andExpect(status().isNotFound()); // even a sibling client of the same creditor
        // a partner token is not a customer or admin token, and vice versa
        mvc.perform(get("/api/v1/accounts").with(from(epal.ip())).header("Authorization", "Bearer " + epal.token())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit").with(from(epal.ip())).header("Authorization", "Bearer " + epal.token())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/partner/collections").with(from(c.ip())).header("Authorization", "Bearer " + c.access()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        // other customers cannot see or change the mandate
        mvc.perform(post("/api/v1/direct-debits/" + m.get("id").asText() + "/cancel").with(from(other.ip())).header("Authorization", "Bearer " + other.access())).andExpect(status().isNotFound());
        assertEquals(0, getJson(other.access(), other.ip(), "/api/v1/direct-debits").size());
    }

    @Test
    void mandatesAreValidatedAndPartnersCanBeProvisionedByAdminsOnly() throws Exception {
        Client c = registerAndLogin();
        mandate(c, "NOPE", "12345678", "100.00", "MONTHLY", 422);
        mandate(c, "UNITEL", "12345678", "100.00", "MONTHLY", 422);   // reference does not fit the creditor's pattern
        String ref = ref();
        mandate(c, "EPAL", ref, "100.00", "MONTHLY", 201);
        mandate(c, "EPAL", ref, "100.00", "MONTHLY", 409);

        Admin root = newAdmin(AdminRole.SUPER_ADMIN), op = newAdmin(AdminRole.OPERATOR);
        String body = json.writeValueAsString(Map.of("creditorCode", "DSTV", "name", "DStv Angola"));
        mvc.perform(post("/api/v1/admin/partners").with(from(op.ip())).header("Authorization", "Bearer " + op.token()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        String creds = mvc.perform(post("/api/v1/admin/partners").with(from(root.ip())).header("Authorization", "Bearer " + root.token()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.clientSecret").exists()).andReturn().getResponse().getContentAsString();
        JsonNode cred = json.readTree(creds);
        mvc.perform(post("/api/v1/partner/auth/token").with(from(newIp())).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("clientId", cred.get("clientId").asText(), "clientSecret", cred.get("clientSecret").asText())))).andExpect(status().isOk());
        assertEquals(1, db.queryForObject("select count(*) from audit_log where action = 'ADMIN_CREATE_PARTNER' and actor_id = ?", Integer.class, root.id()));
        assertFalse(db.queryForObject("select secret_hash from partner where client_id = ?", String.class, cred.get("clientId").asText()).contains(cred.get("clientSecret").asText()));
    }

    private JsonNode statusOf(Creditor cr, String id) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/partner/collections/" + id).with(from(cr.ip())).header("Authorization", "Bearer " + cr.token())).andReturn().getResponse().getContentAsString());
    }
}
