package com.sb24.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sb24.api.jobs.MaintenanceJobs;
import com.sb24.api.service.AccountService;
import com.sb24.api.service.InterestService;
import com.sb24.api.service.LedgerIntegrityService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class SavingsBeneficiaryIT extends BaseIT {
    @Autowired AccountService accounts;
    @Autowired InterestService interest;
    @Autowired LedgerIntegrityService integrity;
    @Autowired MaintenanceJobs jobs;

    private JsonNode post(Client c, String url, Map<String, Object> body, int expected) throws Exception {
        String res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).with(from(c.ip())).header("Authorization", "Bearer " + c.access())
                .header("Idempotency-Key", "idem-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return json.readTree(res);
    }

    private JsonNode subscribe(Client c, String product, String amount, int expected) throws Exception {
        return post(c, "/api/v1/savings", Map.of("fromAccountId", c.accountId(), "productCode", product, "amount", new BigDecimal(amount), "goalName", "Fundo"), expected);
    }

    @Test
    void flexibleSavingsMoveMoneyBothWaysAndKeepTheBooksBalanced() throws Exception {
        Client c = registerAndLogin();
        accounts.cashIn(c.accountId(), new BigDecimal("50000.00"), "test");
        JsonNode sub = subscribe(c, "FLEX", "10000.00", 201);
        UUID sid = UUID.fromString(sub.get("savingsAccountId").asText());
        assertEquals(0, new BigDecimal("10000.00").compareTo(sub.get("savingsBalance").decimalValue()));
        assertEquals(0, new BigDecimal("40000.00").compareTo(sub.get("currentAvailableBalance").decimalValue()));

        post(c, "/api/v1/savings/" + sid + "/deposit", Map.of("currentAccountId", c.accountId(), "amount", new BigDecimal("5000.00")), 201);
        JsonNode out = post(c, "/api/v1/savings/" + sid + "/withdraw", Map.of("currentAccountId", c.accountId(), "amount", new BigDecimal("12000.00")), 201);
        assertEquals(0, new BigDecimal("3000.00").compareTo(out.get("savingsBalance").decimalValue()));
        assertEquals(0, new BigDecimal("47000.00").compareTo(out.get("currentAvailableBalance").decimalValue()));
        post(c, "/api/v1/savings/" + sid + "/withdraw", Map.of("currentAccountId", c.accountId(), "amount", new BigDecimal("999999.00")), 422); // more than the savings hold

        JsonNode list = getJson(c.access(), c.ip(), "/api/v1/savings");
        assertEquals(1, list.size());
        assertEquals("FLEX", list.get(0).get("productCode").asText());
        assertTrue(integrity.verify().ok(), () -> integrity.verify().violations().toString());
    }

    @Test
    void termDepositIsLockedUntilMaturityAndRespectsTheMinimum() throws Exception {
        Client c = registerAndLogin();
        accounts.cashIn(c.accountId(), new BigDecimal("300000.00"), "test");
        assertEquals("BELOW_MINIMUM", subscribe(c, "TERM90", "1000.00", 422).get("code").asText());
        UUID sid = UUID.fromString(subscribe(c, "TERM90", "100000.00", 201).get("savingsAccountId").asText());
        JsonNode denied = post(c, "/api/v1/savings/" + sid + "/withdraw", Map.of("currentAccountId", c.accountId(), "amount", new BigDecimal("1000.00")), 403);
        assertEquals("SAVINGS_LOCKED", denied.get("code").asText());
        db.update("update savings_plan set maturity_date = ? where account_id = ?", LocalDate.now().minusDays(1), sid); // matured
        post(c, "/api/v1/savings/" + sid + "/withdraw", Map.of("currentAccountId", c.accountId(), "amount", new BigDecimal("100000.00")), 201);
    }

    @Test
    void savingsAccountsCannotBeUsedLikeCurrentAccountsOrTouchedByOthers() throws Exception {
        Client c = registerAndLogin(), thief = registerAndLogin(), peer = registerAndLogin();
        accounts.cashIn(c.accountId(), new BigDecimal("20000.00"), "test");
        JsonNode sub = subscribe(c, "FLEX", "5000.00", 201);
        UUID sid = UUID.fromString(sub.get("savingsAccountId").asText());
        String savingsIban = sub.get("savingsIban").asText();
        // cannot be a transfer source or destination, nor a payment source
        post(c, "/api/v1/transfers", Map.of("fromAccountId", sid, "toIban", peer.iban(), "amount", new BigDecimal("10.00")), 403);
        post(c, "/api/v1/transfers", Map.of("fromAccountId", c.accountId(), "toIban", savingsIban, "amount", new BigDecimal("10.00")), 422);
        post(c, "/api/v1/payments", Map.of("fromAccountId", sid, "providerCode", "UNITEL", "reference", "923000000", "amount", new BigDecimal("10.00")), 403);
        // others cannot read, deposit to or withdraw from it
        mvc.perform(get("/api/v1/accounts/" + sid).with(from(thief.ip())).header("Authorization", "Bearer " + thief.access())).andExpect(status().isNotFound());
        post(thief, "/api/v1/savings/" + sid + "/withdraw", Map.of("currentAccountId", thief.accountId(), "amount", new BigDecimal("10.00")), 404);
    }

    @Test
    void dailyInterestIsAccruedOnceAndIsIdempotent() throws Exception {
        Client c = registerAndLogin();
        accounts.cashIn(c.accountId(), new BigDecimal("200000.00"), "test");
        UUID sid = UUID.fromString(subscribe(c, "FLEX", "100000.00", 201).get("savingsAccountId").asText());
        LocalDate day = LocalDate.of(2021, 3, 1);
        interest.accrue(day);
        interest.accrue(day); // second run for the same day must not pay twice
        BigDecimal balance = db.queryForObject("select balance from account where id = ?", BigDecimal.class, sid);
        assertEquals(0, new BigDecimal("100010.96").compareTo(balance), "100000 x 4% / 365 = 10.96");
        assertEquals(1, db.queryForObject("select count(*) from savings_interest where account_id = ?", Integer.class, sid));
        assertTrue(integrity.verify().ok(), () -> integrity.verify().violations().toString());
        // the scheduled path goes through the queue, once per day
        LocalDate other = LocalDate.of(2021, 3, 2);
        assertTrue(jobs.enqueueInterest(other));
        assertFalse(jobs.enqueueInterest(other));
        queueWorker.drain();
        assertEquals(2, db.queryForObject("select count(*) from savings_interest where account_id = ?", Integer.class, sid));
    }

    @Test
    void beneficiariesAreValidatedUniquePrivateAndRemovable() throws Exception {
        Client a = registerAndLogin(), b = registerAndLogin(), other = registerAndLogin();
        Map<String, Object> ok = Map.of("alias", "Maria", "name", "Maria Dos Santos", "iban", b.iban());
        JsonNode created = post(a, "/api/v1/beneficiaries", ok, 201);
        assertEquals(b.iban(), created.get("iban").asText());
        assertEquals("C****** T****", created.get("holderHint").asText()); // masked REAL holder ("Cliente Teste"), not the name typed by the user
        post(a, "/api/v1/beneficiaries", ok, 409);
        String badIban = b.iban().substring(0, 24) + (b.iban().charAt(24) == '0' ? '1' : '0');
        post(a, "/api/v1/beneficiaries", Map.of("alias", "Mau", "name", "Mau Iban", "iban", badIban), 422);
        post(a, "/api/v1/beneficiaries", Map.of("alias", "<b>x</b>", "name", "Xis", "iban", b.iban()), 400);
        assertEquals(0, getJson(other.access(), other.ip(), "/api/v1/beneficiaries").size());
        assertEquals(1, getJson(a.access(), a.ip(), "/api/v1/beneficiaries").size());
        assertFalse(db.queryForObject("select name from beneficiary where id = ?", String.class, UUID.fromString(created.get("id").asText())).contains("Maria"), "name is encrypted at rest");

        String id = created.get("id").asText();
        mvc.perform(delete("/api/v1/beneficiaries/" + id).with(from(other.ip())).header("Authorization", "Bearer " + other.access())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/beneficiaries/" + id).with(from(a.ip())).header("Authorization", "Bearer " + a.access())).andExpect(status().isNoContent());
        assertEquals(0, getJson(a.access(), a.ip(), "/api/v1/beneficiaries").size());
        // someone else's beneficiary id cannot be used as a transfer destination
        UUID foreign = UUID.fromString(post(b, "/api/v1/beneficiaries", Map.of("alias", "Ana", "name", "Ana Pereira", "iban", other.iban()), 201).get("id").asText());
        post(a, "/api/v1/transfers", Map.of("fromAccountId", a.accountId(), "beneficiaryId", foreign, "amount", new BigDecimal("1.00")), 404);
        post(a, "/api/v1/transfers", Map.of("fromAccountId", a.accountId(), "amount", new BigDecimal("1.00")), 400); // no destination at all
    }
}
