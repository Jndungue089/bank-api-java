package com.sb24.api.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed configuration. Secrets come from the environment, never from the repository. */
@ConfigurationProperties("app")
public record AppProperties(Security security, RateLimit rateLimit, Banking banking, Redis redis, Jobs jobs,
                            Queue queue, Admin admin) {

    /**
     * jwtKeyPath: PKCS#8 RSA private key encrypted with PBES2 (PBKDF2-HMAC-SHA256, 310k iterations, AES-256).
     * dataKey: base64 of 32 random bytes, master key for field encryption, blind indexes and ledger MACs.
     */
    public record Security(String jwtKeyPath, String jwtKeyPassphrase, boolean allowKeyGeneration, String dataKey,
                           Duration accessTtl, Duration refreshTtl, int maxFailedLogins, Duration lockDuration,
                           List<String> allowedOrigins, boolean trustForwardHeaders, boolean docsEnabled) {}

    public record Limit(int capacity, Duration period) {}

    public record RateLimit(boolean enabled, Limit ipGlobal, Limit auth, Limit money, Limit general,
                            int softThrottlePercent, long softThrottleDelayMs) {}

    public record Banking(String bankCode, String branchCode, BigDecimal perTransactionLimit,
                          BigDecimal dailyLimit, BigDecimal interbankFeeRate, BigDecimal interbankFeeMin,
                          int maxConcurrentPerUser, int maxGlobalMoneyConcurrency,
                          BigDecimal newBeneficiaryLimit, Duration newBeneficiaryCoolingOff, BigDecimal highValueThreshold,
                          int maxBeneficiaries) {}

    public record Redis(boolean enabled, String uri) {}

    public record Jobs(boolean enabled, String purgeTokensCron, String purgeIdempotencyCron,
                       String ledgerIntegrityCron, String reconciliationCron, String interestCron,
                       Duration idempotencyRetention) {}

    public record Queue(boolean enabled, long pollIntervalMs, int batchSize, int maxAttempts, long backoffBaseMs, Duration lease) {}

    /** Optional first back-office account, created only when no admin exists yet. Dev profile seeds demo admins instead. */
    public record Admin(String bootstrapEmail, String bootstrapPassword) {}
}
