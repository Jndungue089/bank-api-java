package com.sb24.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sb24.api.repo.AccountRepository;
import com.sb24.api.service.AccountService;
import com.sb24.api.service.ApiException;
import com.sb24.api.service.TransferService;
import com.sb24.api.web.Dtos.TransferRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class MoneyIT extends BaseIT {
    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepo;
    @Autowired TransferService transferService;

    private String transferBody(Client from, String toIban, String amount) throws Exception {
        return json.writeValueAsString(Map.of("fromAccountId", from.accountId(), "toIban", toIban, "amount", new BigDecimal(amount)));
    }

    @Test
    void transferMovesMoneyAndIsIdempotent() throws Exception {
        Client a = registerAndLogin(), b = registerAndLogin();
        accountService.cashIn(a.accountId(), new BigDecimal("1000.00"), "test");
        String key = "idem-" + UUID.randomUUID();
        String body = transferBody(a, b.iban(), "250.00");

        mvc.perform(post("/api/v1/transfers").with(from(a.ip())).header("Authorization", "Bearer " + a.access())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.balanceAfter").value(750.00));
        // retry with the same key: replayed, no second debit
        mvc.perform(post("/api/v1/transfers").with(from(a.ip())).header("Authorization", "Bearer " + a.access())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
        // same key, different payload: refused
        mvc.perform(post("/api/v1/transfers").with(from(a.ip())).header("Authorization", "Bearer " + a.access())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(transferBody(a, b.iban(), "999.00")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertEquals(0, new BigDecimal("750.00").compareTo(getJson(a.access(), a.ip(), "/api/v1/accounts/" + a.accountId()).get("balance").decimalValue()));
        assertEquals(0, new BigDecimal("250.00").compareTo(getJson(b.access(), b.ip(), "/api/v1/accounts/" + b.accountId()).get("balance").decimalValue()));
        assertEquals(0, BigDecimal.ZERO.compareTo(accountRepo.totalBalance()), "double-entry books must always sum to zero");
    }

    @Test
    void overdraftAndMissingIdempotencyKeyAreRejected() throws Exception {
        Client a = registerAndLogin(), b = registerAndLogin();
        mvc.perform(post("/api/v1/transfers").with(from(a.ip())).header("Authorization", "Bearer " + a.access())
                .header("Idempotency-Key", "idem-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(transferBody(a, b.iban(), "10.00")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        mvc.perform(post("/api/v1/transfers").with(from(a.ip())).header("Authorization", "Bearer " + a.access())
                .contentType(MediaType.APPLICATION_JSON).content(transferBody(a, b.iban(), "1.00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));
    }

    @Test
    void cannotMoveOrReadSomeoneElsesAccount() throws Exception {
        Client victim = registerAndLogin(), attacker = registerAndLogin();
        accountService.cashIn(victim.accountId(), new BigDecimal("500.00"), "test");
        // read
        mvc.perform(get("/api/v1/accounts/" + victim.accountId()).with(from(attacker.ip())).header("Authorization", "Bearer " + attacker.access()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/accounts/" + victim.accountId() + "/transactions").with(from(attacker.ip())).header("Authorization", "Bearer " + attacker.access()))
                .andExpect(status().isNotFound());
        // spend from it
        mvc.perform(post("/api/v1/transfers").with(from(attacker.ip())).header("Authorization", "Bearer " + attacker.access())
                .header("Idempotency-Key", "idem-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(transferBody(victim, attacker.iban(), "100.00"))).andExpect(status().isNotFound());
    }

    @Test
    void invalidIbanChecksumIsRejected() throws Exception {
        Client a = registerAndLogin();
        accountService.cashIn(a.accountId(), new BigDecimal("100.00"), "test");
        String bad = a.iban().substring(0, 24) + (a.iban().charAt(24) == '0' ? '1' : '0');
        mvc.perform(post("/api/v1/transfers").with(from(a.ip())).header("Authorization", "Bearer " + a.access())
                .header("Idempotency-Key", "idem-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(transferBody(a, bad, "5.00")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_IBAN"));
    }

    /** 40 parallel debits of 10 AOA against a 100 AOA balance: exactly 10 may succeed, never an overdraft. */
    @Test
    void concurrentTransfersCannotOverdrawOrLoseMoney() throws Exception {
        Client a = registerAndLogin(), b = registerAndLogin();
        accountService.cashIn(a.accountId(), new BigDecimal("100.00"), "test");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Boolean>> results = new ArrayList<>();
        UUID userA = UUID.fromString(json.readTree(java.util.Base64.getUrlDecoder().decode(a.access().split("\\.")[1])).get("sub").asText());
        for (int i = 0; i < 40; i++) {
            final int n = i;
            results.add(pool.submit(() -> {
                try {
                    transferService.transfer(userA, new TransferRequest(a.accountId(), b.iban(), new BigDecimal("10.00"), "p" + n),
                            "concurrent-" + n + "-" + UUID.randomUUID(), "127.0.0.1");
                    return true;
                } catch (ApiException e) {
                    return false;
                }
            }));
        }
        long ok = 0;
        for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) ok++;
        pool.shutdown();
        assertEquals(10, ok);
        assertEquals(0, BigDecimal.ZERO.compareTo(accountRepo.findById(a.accountId()).orElseThrow().getBalance()));
        assertEquals(0, new BigDecimal("100.00").compareTo(accountRepo.findById(b.accountId()).orElseThrow().getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(accountRepo.totalBalance()));
    }

    @Test
    void interbankTransferIsReservedThenSettledAsynchronously() throws Exception {
        Client a = registerAndLogin();
        accountService.cashIn(a.accountId(), new BigDecimal("100000.00"), "test");
        String external = com.sb24.api.util.IbanUtil.generate("0040", "0001", "12345678901");
        String res = mvc.perform(post("/api/v1/transfers").with(from(a.ip())).header("Authorization", "Bearer " + a.access())
                .header("Idempotency-Key", "idem-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(transferBody(a, external, "50000.00")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_SETTLEMENT")).andExpect(jsonPath("$.fee").value(50.00))
                .andExpect(jsonPath("$.balanceAfter").value(100000.00))      // saldo contabilístico: unchanged
                .andExpect(jsonPath("$.availableBalance").value(49950.00))   // saldo disponível: reserved already
                .andReturn().getResponse().getContentAsString();
        UUID ref = UUID.fromString(json.readTree(res).get("reference").asText());
        var acc = getJson(a.access(), a.ip(), "/api/v1/accounts/" + a.accountId());
        assertEquals(0, new BigDecimal("50050.00").compareTo(acc.get("heldAmount").decimalValue()));

        queueWorker.drain();

        var settled = getJson(a.access(), a.ip(), "/api/v1/transfers/" + ref);
        assertEquals("SETTLED", settled.get("status").asText());
        var after = getJson(a.access(), a.ip(), "/api/v1/accounts/" + a.accountId());
        assertEquals(0, new BigDecimal("49950.00").compareTo(after.get("balance").decimalValue()));
        assertEquals(0, new BigDecimal("49950.00").compareTo(after.get("availableBalance").decimalValue()));
        assertEquals(0, BigDecimal.ZERO.compareTo(after.get("heldAmount").decimalValue()));
        assertEquals(0, BigDecimal.ZERO.compareTo(accountRepo.totalBalance()));
    }
}
