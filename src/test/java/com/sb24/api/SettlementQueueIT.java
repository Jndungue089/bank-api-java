package com.sb24.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sb24.api.domain.AdminRole;
import com.sb24.api.queue.QueueService;
import com.sb24.api.repo.AccountRepository;
import com.sb24.api.repo.QueueMessageRepository;
import com.sb24.api.service.AccountService;
import com.sb24.api.util.IbanUtil;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class SettlementQueueIT extends BaseIT {
    @Autowired AccountService accounts;
    @Autowired AccountRepository accountRepo;
    @Autowired QueueService queue;
    @Autowired QueueMessageRepository messages;

    private JsonNode transfer(Client c, String bank, String amount, int expectedStatus) throws Exception {
        String to = IbanUtil.generate(bank, "0001", "12345678901");
        String res = mvc.perform(post("/api/v1/transfers").with(from(c.ip())).header("Authorization", "Bearer " + c.access())
                .header("Idempotency-Key", "idem-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fromAccountId", c.accountId(), "toIban", to, "amount", new BigDecimal(amount)))))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    @Test
    void reservedFundsAreNotSpendableTwice() throws Exception {
        Client c = registerAndLogin();
        accounts.cashIn(c.accountId(), new BigDecimal("1000.00"), "test");
        transfer(c, "0040", "900.00", 201);                       // 900 + 50 fee reserved => available 50
        JsonNode second = transfer(c, "0040", "100.00", 422);     // ledger balance is 1000, but only 50 is available
        assertEquals("INSUFFICIENT_FUNDS", second.get("code").asText());
        JsonNode acc = getJson(c.access(), c.ip(), "/api/v1/accounts/" + c.accountId());
        assertEquals(0, new BigDecimal("1000.00").compareTo(acc.get("balance").decimalValue()));
        assertEquals(0, new BigDecimal("50.00").compareTo(acc.get("availableBalance").decimalValue()));
    }

    @Test
    void rejectedSettlementReleasesTheHold() throws Exception {
        Client c = registerAndLogin();
        accounts.cashIn(c.accountId(), new BigDecimal("5000.00"), "test");
        UUID ref = UUID.fromString(transfer(c, "9999", "1000.00", 201).get("reference").asText());
        queueWorker.drain();
        JsonNode st = getJson(c.access(), c.ip(), "/api/v1/transfers/" + ref);
        assertEquals("REJECTED", st.get("status").asText());
        assertEquals("BANCO_DESTINO_DESCONHECIDO", st.get("reason").asText());
        JsonNode acc = getJson(c.access(), c.ip(), "/api/v1/accounts/" + c.accountId());
        assertEquals(0, new BigDecimal("5000.00").compareTo(acc.get("availableBalance").decimalValue()));
        assertEquals(0, new BigDecimal("5000.00").compareTo(acc.get("balance").decimalValue()));
    }

    @Test
    void transientGatewayFailuresAreRetriedWithBackoffUntilSuccess() throws Exception {
        Client c = registerAndLogin();
        accounts.cashIn(c.accountId(), new BigDecimal("5000.00"), "test");
        UUID ref = UUID.fromString(transfer(c, "0050", "1000.00", 201).get("reference").asText()); // times out twice, then accepts
        queueWorker.drain();
        assertEquals("SETTLED", getJson(c.access(), c.ip(), "/api/v1/transfers/" + ref).get("status").asText());
        int attempts = db.queryForObject("select attempts from queue_message where dedup_key = ?", Integer.class, "settle:" + ref);
        assertEquals(3, attempts);
    }

    @Test
    void poisonMessagesGoToDeadLetterAndAdminsCanRequeueThem() throws Exception {
        UUID id = queue.enqueue("NO_SUCH_QUEUE", Map.of("x", 1), null);
        queueWorker.drain();
        assertEquals("DEAD", db.queryForObject("select status from queue_message where id = ?", String.class, id));
        assertEquals(5, db.queryForObject("select attempts from queue_message where id = ?", Integer.class, id));

        Admin op = newAdmin(AdminRole.OPERATOR), auditor = newAdmin(AdminRole.AUDITOR);
        mvc.perform(get("/api/v1/admin/queue/dead").with(from(op.ip())).header("Authorization", "Bearer " + op.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='" + id + "')]").exists());
        String reason = json.writeValueAsString(Map.of("reason", "reprocessar após correcção"));
        mvc.perform(post("/api/v1/admin/queue/" + id + "/requeue").with(from(auditor.ip())).header("Authorization", "Bearer " + auditor.token())
                .contentType(MediaType.APPLICATION_JSON).content(reason)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/queue/" + id + "/requeue").with(from(op.ip())).header("Authorization", "Bearer " + op.token())
                .contentType(MediaType.APPLICATION_JSON).content(reason)).andExpect(status().isNoContent());
        assertEquals("PENDING", db.queryForObject("select status from queue_message where id = ?", String.class, id));
        assertTrue(db.queryForObject("select count(*) from audit_log where action = 'ADMIN_REQUEUE_MESSAGE' and actor_id = ? and resource_id = ?",
                Integer.class, op.id(), id.toString()) == 1);
        queueWorker.drain(); // leaves it dead again, so the shared DB is not left with a pending poison message
        db.update("delete from queue_message where id = ?", id);
    }

    @Test
    void messagesAreEnqueuedAtomicallyWithTheBusinessChange() throws Exception {
        Client c = registerAndLogin();
        long before = messages.count();
        transfer(c, "0040", "10.00", 422); // insufficient funds => rolled back => nothing may be queued
        assertEquals(before, messages.count());
    }

    @Test
    void deliveredNotificationsAreVisibleToTheCustomer() throws Exception {
        Client a = registerAndLogin(), b = registerAndLogin();
        accounts.cashIn(a.accountId(), new BigDecimal("500.00"), "test");
        mvc.perform(post("/api/v1/transfers").with(from(a.ip())).header("Authorization", "Bearer " + a.access())
                .header("Idempotency-Key", "idem-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fromAccountId", a.accountId(), "toIban", b.iban(), "amount", new BigDecimal("100.00")))))
                .andExpect(status().isCreated());
        assertEquals(0, getJson(b.access(), b.ip(), "/api/v1/notifications").get("totalItems").asInt()); // asynchronous: not yet
        queueWorker.drain();
        JsonNode n = getJson(b.access(), b.ip(), "/api/v1/notifications");
        assertEquals(1, n.get("totalItems").asInt());
        assertTrue(n.get("items").get(0).get("message").asText().contains("recebida"));
    }
}
