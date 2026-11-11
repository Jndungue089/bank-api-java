package com.sb24.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sb24.api.config.AppProperties;
import com.sb24.api.domain.AdminRole;
import com.sb24.api.domain.ReconciliationRun;
import com.sb24.api.jobs.MaintenanceJobs;
import com.sb24.api.service.AccountService;
import com.sb24.api.service.LedgerIntegrityService;
import com.sb24.api.service.ReconciliationService;
import java.math.BigDecimal;
import java.time.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.scheduling.support.CronExpression;

class ReconciliationIT extends BaseIT {
    static final ZoneId LUANDA = ZoneId.of("Africa/Luanda");
    @Autowired ReconciliationService reconciliation;
    @Autowired MaintenanceJobs jobs;
    @Autowired AccountService accounts;
    @Autowired AppProperties props;
    @Autowired LedgerIntegrityService integrity;

    private long errorItems(UUID runId) {
        return db.queryForObject("select count(*) from reconciliation_item where run_id = ? and severity = 'ERROR'", Long.class, runId);
    }

    @Test
    void cronFiresAtMidnightInAngolaTime() {
        CronExpression cron = CronExpression.parse(props.jobs().reconciliationCron());
        ZonedDateTime next = cron.next(ZonedDateTime.of(2026, 11, 10, 23, 59, 30, 0, LUANDA));
        assertEquals(ZonedDateTime.of(2026, 11, 11, 0, 0, 0, 0, LUANDA), next);
        assertEquals(Instant.parse("2026-11-10T23:00:00Z"), next.toInstant(), "Angola is UTC+1 all year");
    }

    @Test
    void cleanDayReconcilesAndSnapshotsChainFromDayToDay() throws Exception {
        Client a = registerAndLogin(), b = registerAndLogin();
        accounts.cashIn(a.accountId(), new BigDecimal("1000.00"), "test");
        mvc.perform(post("/api/v1/transfers").with(from(a.ip())).header("Authorization", "Bearer " + a.access()).header("Idempotency-Key", "idem-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("fromAccountId", a.accountId(), "toIban", b.iban(), "amount", new BigDecimal("300.00")))))
                .andExpect(status().isCreated());
        queueWorker.drain();

        LocalDate today = LocalDate.now(LUANDA);
        ReconciliationRun run = reconciliation.run(today, "TEST");
        assertEquals(0, errorItems(run.getId()), () -> db.queryForList("select type, detail from reconciliation_item where run_id = ?", run.getId()).toString());
        assertNotEquals("DISCREPANCIES", run.getStatus());
        BigDecimal closing = db.queryForObject("select closing_balance from daily_snapshot where account_id = ? and business_date = ?", BigDecimal.class, a.accountId(), today);
        assertEquals(0, new BigDecimal("700.00").compareTo(closing));

        // next business day: opening balance is today's closing (from the snapshot), nothing moved, still reconciles
        ReconciliationRun next = reconciliation.run(today.plusDays(1), "TEST");
        assertEquals(0, errorItems(next.getId()));
        BigDecimal opening = db.queryForObject("select opening_balance from daily_snapshot where account_id = ? and business_date = ?", BigDecimal.class, a.accountId(), today.plusDays(1));
        assertEquals(0, closing.compareTo(opening));
    }

    @Test
    void discrepanciesAreDetectedAndReported() throws Exception {
        Client a = registerAndLogin(), b = registerAndLogin();
        accounts.cashIn(a.accountId(), new BigDecimal("1000.00"), "test");
        mvc.perform(post("/api/v1/transfers").with(from(a.ip())).header("Authorization", "Bearer " + a.access()).header("Idempotency-Key", "idem-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("fromAccountId", a.accountId(), "toIban", b.iban(), "amount", new BigDecimal("100.00")))))
                .andExpect(status().isCreated());
        LocalDate today = LocalDate.now(LUANDA);

        db.update("update ledger_entry set amount = amount + 5 where account_id = ? and seq = 2", a.accountId()); // forged amount
        db.update("update account set held = held + 7 where id = ?", b.accountId());                              // phantom reservation
        try {
            ReconciliationRun run = reconciliation.run(today, "TEST");
            assertEquals("DISCREPANCIES", run.getStatus());
            var types = db.queryForList("select type from reconciliation_item where run_id = ?", String.class, run.getId());
            assertTrue(types.contains("BALANCE_MISMATCH"), types.toString());
            assertTrue(types.contains("UNBALANCED_TRANSACTION"), types.toString());
            assertTrue(types.contains("HOLD_MISMATCH"), types.toString());
            assertEquals(1, db.queryForObject("select count(*) from audit_log where action = 'RECONCILIATION' and outcome = 'FAILED' and resource_id = ?", Integer.class, run.getId().toString()));
        } finally {
            db.update("update ledger_entry set amount = amount - 5 where account_id = ? and seq = 2", a.accountId());
            db.update("update account set held = held - 7 where id = ?", b.accountId());
        }
        assertEquals(0, errorItems(reconciliation.run(today, "TEST").getId()));
    }

    @Test
    void auditLogTamperingIsDetectedByTheIntegrityCheck() throws Exception {
        Client a = registerAndLogin();
        UUID id = db.queryForObject("select id from audit_log where action = 'LOGIN' and actor_id = ? limit 1", UUID.class, subject(a.access()));
        db.update("update audit_log set outcome = 'FAILED' where id = ?", id);
        try {
            LedgerIntegrityService.Report r = integrity.verify();
            assertFalse(r.ok());
            assertTrue(r.violations().stream().anyMatch(v -> v.contains(id.toString())));
        } finally {
            db.update("update audit_log set outcome = 'SUCCESS' where id = ?", id);
        }
        assertTrue(integrity.verify().ok(), () -> integrity.verify().violations().toString());
    }

    @Test
    void scheduledRunIsQueuedOncePerDayAndExecutedByTheWorker() {
        LocalDate day = LocalDate.of(2020, 1, 15); // a date no other test uses
        assertTrue(jobs.enqueueReconciliation(day, "CRON"));
        assertFalse(jobs.enqueueReconciliation(day, "CRON"), "second instance / second tick must not queue a duplicate");
        queueWorker.drain();
        assertEquals(1, db.queryForObject("select count(*) from reconciliation_run where business_date = ? and triggered_by = 'CRON'", Integer.class, day));
    }

    @Test
    void adminCanRequestAReconciliationAndSeeTheReport() throws Exception {
        Admin op = newAdmin(AdminRole.OPERATOR), auditor = newAdmin(AdminRole.AUDITOR);
        Client c = registerAndLogin();
        String day = LocalDate.of(2020, 2, 20).toString();
        mvc.perform(post("/api/v1/admin/reconciliation/run?date=" + day).with(from(c.ip())).header("Authorization", "Bearer " + c.access())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/reconciliation/run?date=" + day).with(from(auditor.ip())).header("Authorization", "Bearer " + auditor.token())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/reconciliation/run?date=" + LocalDate.now(LUANDA).plusDays(3)).with(from(op.ip())).header("Authorization", "Bearer " + op.token())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/reconciliation/run?date=" + day).with(from(op.ip())).header("Authorization", "Bearer " + op.token()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED"));
        queueWorker.drain();
        String runs = mvc.perform(get("/api/v1/admin/reconciliation/runs?size=100").with(from(auditor.ip())).header("Authorization", "Bearer " + auditor.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.businessDate=='" + day + "')]").exists()).andReturn().getResponse().getContentAsString();
        String runId = json.readTree(runs).get("items").findValues("id").stream().filter(n -> true).findFirst().orElseThrow().asText();
        mvc.perform(get("/api/v1/admin/reconciliation/runs/" + runId).with(from(auditor.ip())).header("Authorization", "Bearer " + auditor.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.run.id").value(runId));
        assertEquals(1, db.queryForObject("select count(*) from audit_log where action = 'ADMIN_RUN_RECONCILIATION' and actor_id = ?", Integer.class, op.id()));
    }
}
