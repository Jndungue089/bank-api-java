package com.sb24.api;

import static org.junit.jupiter.api.Assertions.*;

import com.sb24.api.domain.RefreshToken;
import com.sb24.api.jobs.MaintenanceJobs;
import com.sb24.api.redis.JobLock;
import com.sb24.api.repo.RefreshTokenRepository;
import com.sb24.api.service.AccountService;
import com.sb24.api.service.LedgerIntegrityService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class IntegrityAndJobsIT extends BaseIT {
    @Autowired LedgerIntegrityService integrity;
    @Autowired MaintenanceJobs jobs;
    @Autowired AccountService accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobLock lock;
    @Autowired RefreshTokenRepository refreshTokens;

    @Test
    void untouchedLedgerVerifiesAndTamperingIsDetected() throws Exception {
        Client c = registerAndLogin();
        accounts.cashIn(c.accountId(), new BigDecimal("1000.00"), "test");
        assertTrue(integrity.verify().ok(), () -> integrity.verify().violations().toString());

        // an attacker with direct DB access rewrites history (amount of one ledger row)
        jdbc.update("update ledger_entry set amount = amount + 1 where account_id = ? and seq = 1", c.accountId());
        LedgerIntegrityService.Report tampered = integrity.verify();
        try {
            assertFalse(tampered.ok());
            assertTrue(tampered.violations().stream().anyMatch(v -> v.contains("tampered entry")));
        } finally {
            jdbc.update("update ledger_entry set amount = amount - 1 where account_id = ? and seq = 1", c.accountId());
        }
        assertTrue(integrity.verify().ok());

        // re-ordering history (sequence shift) is detected as well
        jdbc.update("update ledger_entry set seq = seq + 10 where account_id = ?", c.accountId());
        try {
            assertTrue(integrity.verify().violations().stream().anyMatch(v -> v.contains("gap or reorder")));
        } finally {
            jdbc.update("update ledger_entry set seq = seq - 10 where account_id = ?", c.accountId());
        }
        assertTrue(integrity.verify().ok());
    }

    @Test
    void purgeJobRemovesOnlyExpiredRefreshTokens() throws Exception {
        Client c = registerAndLogin();
        UUID userId = UUID.fromString(json.readTree(Base64Url.decode(c.access().split("\\.")[1])).get("sub").asText());
        RefreshToken old = new RefreshToken();
        old.setUserId(userId);
        old.setFamilyId(UUID.randomUUID());
        old.setTokenHash(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        old.setExpiresAt(Instant.now().minus(Duration.ofDays(1)));
        refreshTokens.save(old);
        long before = refreshTokens.count();
        jobs.purgeRefreshTokens();
        assertFalse(refreshTokens.existsById(old.getId()));
        assertEquals(before - 1, refreshTokens.count(), "unexpired tokens must survive");
    }

    @Test
    void jobLockIsMutuallyExclusive() {
        AtomicBoolean innerRan = new AtomicBoolean();
        boolean outer = lock.runExclusive("t-" + UUID.randomUUID(), Duration.ofSeconds(30), () -> { });
        assertTrue(outer);
        String name = "nested-" + UUID.randomUUID();
        lock.runExclusive(name, Duration.ofSeconds(30), () -> innerRan.set(lock.runExclusive(name, Duration.ofSeconds(30), () -> { })));
        assertFalse(innerRan.get(), "second concurrent run of the same job must be skipped");
        assertTrue(lock.runExclusive(name, Duration.ofSeconds(30), () -> { }), "lock is released after the run");
    }

    private static final class Base64Url {
        static byte[] decode(String s) { return java.util.Base64.getUrlDecoder().decode(s); }
    }
}
