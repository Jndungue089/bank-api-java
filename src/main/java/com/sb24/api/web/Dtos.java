package com.sb24.api.web;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Dtos {
    private Dtos() {}

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "^[\\p{L} .'’-]{3,120}$", message = "nome inválido") String fullName,
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Pattern(regexp = "^\\+?\\d{9,15}$", message = "telefone inválido") String phone,
            @NotBlank @Pattern(regexp = "^\\d{9}[A-Z]{2}\\d{3}$", message = "formato do BI inválido") String nationalId,
            @NotBlank @Size(min = 12, max = 128) String password) {}

    public record LoginRequest(@NotBlank @Email @Size(max = 160) String email, @NotBlank @Size(max = 128) String password) {}

    public record RefreshRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{40,100}$", message = "token inválido") String refreshToken) {}

    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {}

    /** balance = saldo contabilístico (ledger); heldAmount = cativo; availableBalance = balance - heldAmount (spendable). */
    public record AccountView(UUID id, String iban, String type, String currency, BigDecimal balance, BigDecimal heldAmount,
                              BigDecimal availableBalance, String status) {}

    public record ProfileView(UUID id, String fullName, String email, String phone, List<AccountView> accounts) {}

    public record LedgerView(UUID id, UUID reference, String kind, String direction, BigDecimal amount,
                             BigDecimal balanceAfter, String counterpartyIban, String description, Instant createdAt) {}

    public record PageView<T>(List<T> items, int page, int size, long totalItems, int totalPages) {}

    public record TransferRequest(
            @NotNull UUID fromAccountId,
            @Pattern(regexp = "^AO\\d{23}$", message = "IBAN inválido") String toIban,
            UUID beneficiaryId,
            @NotNull @DecimalMin("0.01") @DecimalMax("999999999999999.99") @Digits(integer = 15, fraction = 2) BigDecimal amount,
            @Size(max = 140) @SafeText String description) {
        public TransferRequest(UUID fromAccountId, String toIban, BigDecimal amount, String description) { this(fromAccountId, toIban, null, amount, description); }
    }

    public record PaymentRequest(
            @NotNull UUID fromAccountId,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2,12}$", message = "código inválido") String providerCode,
            @NotBlank @Pattern(regexp = "^\\d{8,12}$", message = "referência inválida") String reference,
            @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount) {}

    public record Receipt(UUID reference, String status, String type, BigDecimal amount, BigDecimal fee,
                          String fromIban, String toIban, BigDecimal balanceAfter, BigDecimal availableBalance, Instant createdAt) {}

    public record ProviderView(String code, String name, String referenceHint) {}

    public record AuditView(UUID id, String actorType, UUID actorId, String action, String resourceType, String resourceId,
                            String outcome, String httpMethod, String path, Integer httpStatus, String ip, String deviceId,
                            String userAgent, String requestId, String detail, Instant createdAt) {}

    public record NotificationView(UUID id, String channel, String message, Instant createdAt) {}

    public record TransferStatusView(UUID reference, String status, String reason, BigDecimal amount, BigDecimal fee,
                                     String toIban, Instant createdAt, Instant settledAt) {}

    public record AdminLoginRequest(@NotBlank @Email @Size(max = 160) String email, @NotBlank @Size(max = 128) String password) {}

    public record CreateAdminRequest(
            @NotBlank @Pattern(regexp = "^[\\p{L} .'’-]{3,120}$", message = "nome inválido") String fullName,
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(min = 14, max = 128) String password,
            @NotNull com.sb24.api.domain.AdminRole role) {}

    public record AdminView(UUID id, String email, String fullName, java.util.Set<String> roles, boolean enabled, Instant lastLoginAt) {}

    /** Mandatory justification for every state-changing admin action; stored in the audit trail. */
    public record ReasonRequest(@NotBlank @Size(min = 5, max = 200) @SafeText String reason) {}

    public record CustomerSummary(UUID id, String email, String name, boolean enabled, Instant createdAt) {}

    public record CustomerDetail(UUID id, String email, String fullName, String phone, String nationalId, boolean enabled,
                                 Instant lockedUntil, Instant createdAt, List<AccountView> accounts) {}

    public record ReconciliationRunView(UUID id, java.time.LocalDate businessDate, String triggeredBy, String status,
                                        int accountsChecked, long entriesChecked, BigDecimal totalDebits, BigDecimal totalCredits,
                                        int discrepancies, Instant startedAt, Instant finishedAt) {}

    public record ReconciliationItemView(String severity, String type, UUID accountId, BigDecimal expected, BigDecimal actual, String detail) {}

    public record ReconciliationDetail(ReconciliationRunView run, List<ReconciliationItemView> items) {}

    public record QueueMessageView(UUID id, String queue, String status, int attempts, int maxAttempts, String lastError,
                                   Instant createdAt, Instant nextAttemptAt) {}

    public record BeneficiaryRequest(
            @NotBlank @Pattern(regexp = "^[\\p{L}\\p{N} .'’-]{2,40}$", message = "alias inválido") String alias,
            @NotBlank @Pattern(regexp = "^[\\p{L} .'’-]{3,120}$", message = "nome inválido") String name,
            @NotBlank @Pattern(regexp = "^AO\\d{23}$", message = "IBAN inválido") String iban) {}

    /** holderHint: for same-bank IBANs, the masked name of the real account holder so the customer can double-check. */
    public record BeneficiaryView(UUID id, String alias, String name, String iban, String bankCode, String holderHint,
                                  Instant createdAt, Instant lastUsedAt) {}

    public record SavingsProductView(String code, String name, BigDecimal annualRate, BigDecimal minAmount, int termDays) {}

    public record SubscribeSavingsRequest(
            @NotNull UUID fromAccountId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{2,12}$") String productCode,
            @NotNull @DecimalMin("0.01") @DecimalMax("999999999999999.99") @Digits(integer = 15, fraction = 2) BigDecimal amount,
            @Size(max = 60) @SafeText String goalName) {}

    public record SavingsMoveRequest(
            @NotNull UUID currentAccountId,
            @NotNull @DecimalMin("0.01") @DecimalMax("999999999999999.99") @Digits(integer = 15, fraction = 2) BigDecimal amount) {}

    public record SavingsView(UUID accountId, String iban, String productCode, String productName, String goalName, BigDecimal annualRate,
                              java.time.LocalDate maturityDate, BigDecimal balance, String status, Instant openedAt) {}

    public record SavingsReceipt(UUID reference, String type, UUID savingsAccountId, String savingsIban, BigDecimal amount,
                                 BigDecimal savingsBalance, BigDecimal currentAvailableBalance, Instant createdAt) {}

    public record MandateRequest(
            @NotNull UUID accountId,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2,12}$") String creditorCode,
            @NotBlank @Pattern(regexp = "^\\d{8,12}$", message = "referência inválida") String creditorReference,
            @NotNull @DecimalMin("0.01") @DecimalMax("999999999999999.99") @Digits(integer = 15, fraction = 2) BigDecimal maxAmount,
            @NotNull com.sb24.api.domain.DirectDebitMandate.Frequency frequency) {}

    public record MandateView(UUID id, UUID accountId, String creditorCode, String creditorName, String creditorReference,
                              BigDecimal maxAmount, String frequency, String status, Instant createdAt) {}

    public record CollectionView(UUID id, UUID mandateId, String externalRef, BigDecimal amount, java.time.LocalDate dueDate,
                                 String status, String reason, Instant createdAt, Instant processedAt) {}

    public record PartnerTokenRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{6,40}$") String clientId, @NotBlank @Size(max = 128) String clientSecret) {}

    public record CollectionRequest(
            @NotBlank @Pattern(regexp = "^\\d{8,12}$", message = "referência inválida") String creditorReference,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{6,40}$") String externalRef,
            @NotNull @DecimalMin("0.01") @DecimalMax("999999999999999.99") @Digits(integer = 15, fraction = 2) BigDecimal amount,
            @NotNull java.time.LocalDate dueDate) {}

    public record PermissionView(String code, String description) {}

    public record RoleView(String code, String realm, String description, List<String> permissions) {}

    public record RbacCatalog(List<RoleView> roles, List<PermissionView> permissions) {}

    public record SubjectAccess(UUID subjectId, String realm, java.util.Set<String> roles, java.util.Set<String> permissions) {}

    public record RoleChangeRequest(@NotBlank @Pattern(regexp = "^[A-Z_]{3,30}$") String role, @NotBlank @Size(min = 5, max = 200) @SafeText String reason) {}

    public record CreatePartnerRequest(@NotBlank @Pattern(regexp = "^[A-Za-z]{2,12}$") String creditorCode,
                                       @NotBlank @Pattern(regexp = "^[\\p{L}\\p{N} .'’&-]{3,80}$") String name) {}

    public record PartnerCredentials(UUID partnerId, String creditorCode, String clientId, String clientSecret, String note) {}

    public record PartnerView(UUID id, String creditorCode, String name, String clientId, boolean enabled) {}

    public record QueueStats(long pending, long processing, long done, long dead) {}
}
