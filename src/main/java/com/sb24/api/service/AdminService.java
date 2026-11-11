package com.sb24.api.service;

import com.sb24.api.domain.*;
import com.sb24.api.queue.QueueService;
import com.sb24.api.repo.*;
import com.sb24.api.web.Dtos.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Back-office operations. Every method writes an ADMIN audit row naming the acting admin, the target resource and
 * (for state changes) the mandatory reason. Reading personal data is itself an audited action.
 */
@Service
public class AdminService {
    private final UserRepository users;
    private final AccountRepository accounts;
    private final AdminUserRepository admins;
    private final QueueMessageRepository queueMessages;
    private final AuditLogRepository auditLogs;
    private final ReconciliationRunRepository runs;
    private final ReconciliationItemRepository runItems;
    private final AuditService audit;
    private final PasswordEncoder encoder;
    private final RbacService rbac;
    private final com.sb24.api.access.PolicyService policy;
    private final com.sb24.api.access.PermissionService permissions;
    private final PartnerRepository partners;
    private final PaymentProviderRepository providers;

    public AdminService(UserRepository users, AccountRepository accounts, AdminUserRepository admins, QueueMessageRepository queueMessages,
                        AuditLogRepository auditLogs, ReconciliationRunRepository runs, ReconciliationItemRepository runItems,
                        AuditService audit, PasswordEncoder encoder, RbacService rbac, com.sb24.api.access.PolicyService policy,
                        com.sb24.api.access.PermissionService permissions, PartnerRepository partners, PaymentProviderRepository providers) {
        this.rbac = rbac;
        this.policy = policy;
        this.permissions = permissions;
        this.partners = partners;
        this.providers = providers;
        this.users = users;
        this.accounts = accounts;
        this.admins = admins;
        this.queueMessages = queueMessages;
        this.auditLogs = auditLogs;
        this.runs = runs;
        this.runItems = runItems;
        this.audit = audit;
        this.encoder = encoder;
    }

    @Transactional
    public PageView<CustomerSummary> customers(UUID adminId, int page, int size) {
        Page<AppUser> p = users.findByRole(Role.CUSTOMER, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        audit.admin(adminId, "ADMIN_LIST_CUSTOMERS", "SUCCESS", "CUSTOMER", null, "page=" + page);
        return new PageView<>(p.map(u -> new CustomerSummary(u.getId(), u.getEmail(), mask(u.getFullName()), u.isEnabled(), u.getCreatedAt())).getContent(),
                p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    @Transactional
    public CustomerDetail customer(UUID adminId, UUID customerId) {
        AppUser u = find(customerId);
        audit.admin(adminId, "ADMIN_VIEW_CUSTOMER", "SUCCESS", "CUSTOMER", customerId.toString(), "personal data viewed (masked)");
        List<AccountView> accs = accounts.findByOwnerIdOrderByCreatedAtAsc(customerId).stream().map(AccountService::view).toList();
        return new CustomerDetail(u.getId(), u.getEmail(), u.getFullName(), maskKeep(u.getPhone(), 3), maskKeep(u.getNationalId(), 3),
                u.isEnabled(), u.getLockedUntil(), u.getCreatedAt(), accs);
    }

    /** Blocking a customer disables the login and freezes every account of theirs. */
    @Transactional
    public void setCustomerBlocked(UUID adminId, UUID customerId, boolean blocked, String reason) {
        AppUser u = find(customerId);
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("operation", blocked ? "BLOCK" : "UNBLOCK");
        attrs.put("blockedBy", u.getBlockedBy());
        policy.require(com.sb24.api.access.Subject.admin(adminId), com.sb24.api.access.Perm.CUSTOMER_BLOCK, com.sb24.api.access.Resource.of("CUSTOMER", customerId, null, attrs));
        u.setEnabled(!blocked);
        u.setBlockedBy(blocked ? adminId : null);
        u.setBlockedReason(blocked ? reason : null);
        u.setBlockedAt(blocked ? Instant.now() : null);
        for (Account a : accounts.findByOwnerIdOrderByCreatedAtAsc(customerId)) a.setStatus(blocked ? "FROZEN" : "ACTIVE");
        audit.admin(adminId, blocked ? "ADMIN_BLOCK_CUSTOMER" : "ADMIN_UNBLOCK_CUSTOMER", "SUCCESS", "CUSTOMER", customerId.toString(), "reason=" + reason);
    }

    @Transactional
    public void setAccountFrozen(UUID adminId, UUID accountId, boolean frozen, String reason) {
        Account a = accounts.findById(accountId).orElseThrow(() -> ApiException.notFound("Conta não encontrada."));
        if (a.getType() != AccountType.CURRENT) throw ApiException.unprocessable("NOT_A_CUSTOMER_ACCOUNT", "Só contas de clientes podem ser congeladas.");
        a.setStatus(frozen ? "FROZEN" : "ACTIVE");
        audit.admin(adminId, frozen ? "ADMIN_FREEZE_ACCOUNT" : "ADMIN_UNFREEZE_ACCOUNT", "SUCCESS", "ACCOUNT", accountId.toString(), "reason=" + reason);
    }

    @Transactional
    public AdminView createAdmin(UUID actorId, CreateAdminRequest r) {
        String email = r.email().trim().toLowerCase();
        if (admins.existsByEmail(email)) {
            audit.admin(actorId, "ADMIN_CREATE_ADMIN", "DENIED", "ADMIN", null, "duplicate email");
            throw ApiException.conflict("ADMIN_EXISTS", "Já existe um administrador com esse email.");
        }
        AdminUser a = newAdmin(r.fullName().trim(), email, r.password(), r.role(), actorId);
        audit.admin(actorId, "ADMIN_CREATE_ADMIN", "SUCCESS", "ADMIN", a.getId().toString(), "role=" + r.role());
        return adminView(a);
    }

    /** Also used by the seeders (which audit as SYSTEM). */
    @Transactional
    public AdminUser newAdmin(String name, String email, String password, AdminRole role, UUID createdBy) {
        AdminUser a = new AdminUser();
        a.setFullName(name);
        a.setEmail(email);
        a.setPasswordHash(encoder.encode(password));
        a.setCreatedBy(createdBy);
        admins.save(a);
        rbac.grant(a.getId(), role.name(), createdBy);
        return a;
    }

    @Transactional
    public List<AdminView> listAdmins(UUID adminId) {
        audit.admin(adminId, "ADMIN_LIST_ADMINS", "SUCCESS", "ADMIN", null, null);
        return admins.findAll().stream().map(this::adminView).toList();
    }

    @Transactional
    public PageView<AuditView> auditTrail(UUID adminId, UUID actorId, String action, int page, int size) {
        Page<AuditLog> p = auditLogs.search(actorId, action, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));
        audit.admin(adminId, "ADMIN_VIEW_AUDIT", "SUCCESS", "AUDIT_LOG", null, "filter actor=" + actorId + " action=" + action);
        return new PageView<>(p.map(AdminService::view).getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    @Transactional
    public PageView<ReconciliationRunView> reconciliationRuns(UUID adminId, int page, int size) {
        Page<ReconciliationRun> p = runs.findAllByOrderByStartedAtDesc(PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        audit.admin(adminId, "ADMIN_VIEW_RECONCILIATION", "SUCCESS", "RECONCILIATION_RUN", null, null);
        return new PageView<>(p.map(AdminService::view).getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    @Transactional
    public ReconciliationDetail reconciliationRun(UUID adminId, UUID runId) {
        ReconciliationRun r = runs.findById(runId).orElseThrow(() -> ApiException.notFound("Conciliação não encontrada."));
        audit.admin(adminId, "ADMIN_VIEW_RECONCILIATION", "SUCCESS", "RECONCILIATION_RUN", runId.toString(), null);
        return new ReconciliationDetail(view(r), runItems.findByRunId(runId).stream()
                .map(i -> new ReconciliationItemView(i.getSeverity(), i.getType(), i.getAccountId(), i.getExpected(), i.getActual(), i.getDetail())).toList());
    }

    @Transactional
    public QueueStats queueStats(UUID adminId) {
        audit.admin(adminId, "ADMIN_VIEW_QUEUE", "SUCCESS", "QUEUE", null, null);
        return new QueueStats(queueMessages.countByStatus(QueueMessage.Status.PENDING), queueMessages.countByStatus(QueueMessage.Status.PROCESSING),
                queueMessages.countByStatus(QueueMessage.Status.DONE), queueMessages.countByStatus(QueueMessage.Status.DEAD));
    }

    @Transactional
    public List<QueueMessageView> deadLetters(UUID adminId) {
        audit.admin(adminId, "ADMIN_VIEW_QUEUE", "SUCCESS", "QUEUE", null, "dead letters");
        return queueMessages.findByStatusOrderByCreatedAtDesc(QueueMessage.Status.DEAD, PageRequest.of(0, 100)).stream().map(m ->
                new QueueMessageView(m.getId(), m.getQueue(), m.getStatus().name(), m.getAttempts(), m.getMaxAttempts(), m.getLastError(), m.getCreatedAt(), m.getNextAttemptAt())).toList();
    }

    @Transactional
    public void requeue(UUID adminId, UUID messageId, String reason) {
        QueueMessage m = queueMessages.findById(messageId).orElseThrow(() -> ApiException.notFound("Mensagem não encontrada."));
        if (m.getStatus() != QueueMessage.Status.DEAD) throw ApiException.unprocessable("NOT_DEAD", "Só mensagens em dead-letter podem ser reenfileiradas.");
        m.setStatus(QueueMessage.Status.PENDING);
        m.setAttempts(0);
        m.setNextAttemptAt(Instant.now());
        audit.admin(adminId, "ADMIN_REQUEUE_MESSAGE", "SUCCESS", "QUEUE_MESSAGE", messageId.toString(), "queue=" + m.getQueue() + " reason=" + reason);
    }

    @Transactional
    public void auditManualReconciliation(UUID adminId, java.time.LocalDate date) {
        audit.admin(adminId, "ADMIN_RUN_RECONCILIATION", "SUCCESS", "RECONCILIATION_RUN", null, "date=" + date);
    }

    private AppUser find(UUID id) {
        return users.findById(id).filter(u -> u.getRole() == Role.CUSTOMER).orElseThrow(() -> ApiException.notFound("Cliente não encontrado."));
    }

    private static String mask(String name) {
        if (name == null) return null;
        StringBuilder sb = new StringBuilder();
        for (String part : name.split(" ")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(part.isEmpty() ? "" : part.charAt(0)).append("*".repeat(Math.max(0, Math.min(part.length() - 1, 6))));
        }
        return sb.toString();
    }

    private static String maskKeep(String v, int keep) {
        if (v == null || v.length() <= keep) return v;
        return "*".repeat(v.length() - keep) + v.substring(v.length() - keep);
    }

    AdminView adminView(AdminUser a) {
        return new AdminView(a.getId(), a.getEmail(), a.getFullName(), new java.util.TreeSet<>(permissions.rolesOf(a.getId())), a.isEnabled(), a.getLastLoginAt());
    }

    @Transactional
    public PartnerCredentials createPartner(UUID adminId, CreatePartnerRequest r) {
        PaymentProvider provider = providers.findById(r.creditorCode().toUpperCase()).filter(PaymentProvider::isDirectDebit)
                .orElseThrow(() -> ApiException.unprocessable("CREDITOR_NOT_SUPPORTED", "Entidade desconhecida ou sem débito directo."));
        byte[] rnd = new byte[33];
        new java.security.SecureRandom().nextBytes(rnd);
        String secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
        String clientId = provider.getCode().toLowerCase() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Partner p = newPartner(provider.getCode(), r.name().trim(), clientId, secret, adminId);
        audit.admin(adminId, "ADMIN_CREATE_PARTNER", "SUCCESS", "PARTNER", p.getId().toString(), "creditor=" + provider.getCode());
        return new PartnerCredentials(p.getId(), provider.getCode(), clientId, secret, "Guarde o segredo agora: não volta a ser mostrado.");
    }

    @Transactional
    public Partner newPartner(String creditorCode, String name, String clientId, String secret, UUID createdBy) {
        Partner p = new Partner();
        p.setCreditorCode(creditorCode);
        p.setName(name);
        p.setClientId(clientId);
        p.setSecretHash(encoder.encode(secret));
        partners.save(p);
        rbac.grant(p.getId(), com.sb24.api.access.Rbac.CREDITOR, createdBy);
        return p;
    }

    @Transactional
    public List<PartnerView> listPartners(UUID adminId) {
        audit.admin(adminId, "ADMIN_LIST_PARTNERS", "SUCCESS", "PARTNER", null, null);
        return partners.findAll().stream().map(p -> new PartnerView(p.getId(), p.getCreditorCode(), p.getName(), p.getClientId(), p.isEnabled())).toList();
    }

    static AuditView view(AuditLog e) {
        return new AuditView(e.getId(), e.getActorType().name(), e.getActorId(), e.getAction(), e.getResourceType(), e.getResourceId(), e.getOutcome(),
                e.getHttpMethod(), e.getPath(), e.getHttpStatus(), e.getIp(), e.getDeviceId(), e.getUserAgent(), e.getRequestId(), e.getDetail(), e.getCreatedAt());
    }

    static ReconciliationRunView view(ReconciliationRun r) {
        return new ReconciliationRunView(r.getId(), r.getBusinessDate(), r.getTriggeredBy(), r.getStatus(), r.getAccountsChecked(), r.getEntriesChecked(),
                r.getTotalDebits(), r.getTotalCredits(), r.getDiscrepancies(), r.getStartedAt(), r.getFinishedAt());
    }
}
