package com.sb24.api.web;

import com.sb24.api.access.Perm;
import com.sb24.api.access.RequiresPermission;
import com.sb24.api.jobs.MaintenanceJobs;
import com.sb24.api.service.AdminService;
import com.sb24.api.service.ApiException;
import com.sb24.api.service.LedgerIntegrityService;
import com.sb24.api.service.RbacService;
import com.sb24.api.web.Dtos.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Every handler declares its permission; the permission is looked up in the DB for the calling admin on each request. */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Administração")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService admin;
    private final RbacService rbac;
    private final MaintenanceJobs jobs;

    public AdminController(AdminService admin, RbacService rbac, @Autowired(required = false) MaintenanceJobs jobs) {
        this.admin = admin;
        this.rbac = rbac;
        this.jobs = jobs;
    }

    @Operation(summary = "Trilho de auditoria (filtros: actorId, action)")
    @RequiresPermission(Perm.AUDIT_READ)
    @GetMapping("/audit")
    public PageView<AuditView> audit(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID actorId,
                                     @RequestParam(required = false) @jakarta.validation.constraints.Pattern(regexp = "^[A-Z_]{3,50}$") String action,
                                     @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return admin.auditTrail(uid(jwt), actorId, action, page, size);
    }

    @Operation(summary = "Verificar integridade do livro razão e do trilho de auditoria")
    @RequiresPermission(Perm.INTEGRITY_CHECK)
    @PostMapping("/integrity-check")
    public LedgerIntegrityService.Report integrity() {
        if (jobs == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "JOBS_DISABLED", "Jobs desactivados.");
        return jobs.runIntegrityCheck();
    }

    @Operation(summary = "Listar clientes (nomes mascarados)")
    @RequiresPermission(Perm.CUSTOMER_LIST)
    @GetMapping("/customers")
    public PageView<CustomerSummary> customers(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return admin.customers(uid(jwt), page, size);
    }

    @Operation(summary = "Ver cliente (dados pessoais parcialmente mascarados; a consulta é auditada)")
    @RequiresPermission(Perm.CUSTOMER_PII_READ)
    @GetMapping("/customers/{id}")
    public CustomerDetail customer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return admin.customer(uid(jwt), id); }

    @Operation(summary = "Bloquear cliente e congelar as suas contas")
    @RequiresPermission(Perm.CUSTOMER_BLOCK)
    @PostMapping("/customers/{id}/block")
    public ResponseEntity<Void> block(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        admin.setCustomerBlocked(uid(jwt), id, true, r.reason());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Desbloquear cliente (quem bloqueou não pode desbloquear, salvo SUPER_ADMIN)")
    @RequiresPermission(Perm.CUSTOMER_BLOCK)
    @PostMapping("/customers/{id}/unblock")
    public ResponseEntity<Void> unblock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        admin.setCustomerBlocked(uid(jwt), id, false, r.reason());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Congelar conta")
    @RequiresPermission(Perm.ACCOUNT_FREEZE)
    @PostMapping("/accounts/{id}/freeze")
    public ResponseEntity<Void> freeze(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        admin.setAccountFrozen(uid(jwt), id, true, r.reason());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Descongelar conta")
    @RequiresPermission(Perm.ACCOUNT_FREEZE)
    @PostMapping("/accounts/{id}/unfreeze")
    public ResponseEntity<Void> unfreeze(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        admin.setAccountFrozen(uid(jwt), id, false, r.reason());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Criar administrador")
    @RequiresPermission(Perm.ADMIN_MANAGE)
    @PostMapping("/admins")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminView createAdmin(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateAdminRequest r) { return admin.createAdmin(uid(jwt), r); }

    @Operation(summary = "Listar administradores")
    @RequiresPermission(Perm.ADMIN_READ)
    @GetMapping("/admins")
    public List<AdminView> admins(@AuthenticationPrincipal Jwt jwt) { return admin.listAdmins(uid(jwt)); }

    @Operation(summary = "Criar credor (parceiro de débito directo); devolve o segredo uma única vez")
    @RequiresPermission(Perm.PARTNER_MANAGE)
    @PostMapping("/partners")
    @ResponseStatus(HttpStatus.CREATED)
    public PartnerCredentials createPartner(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreatePartnerRequest r) { return admin.createPartner(uid(jwt), r); }

    @Operation(summary = "Listar credores")
    @RequiresPermission(Perm.PARTNER_MANAGE)
    @GetMapping("/partners")
    public List<PartnerView> partners(@AuthenticationPrincipal Jwt jwt) { return admin.listPartners(uid(jwt)); }

    @Operation(summary = "Catálogo de papéis e permissões")
    @RequiresPermission(Perm.RBAC_READ)
    @GetMapping("/rbac")
    public RbacCatalog rbacCatalog() { return rbac.catalog(); }

    @Operation(summary = "Papéis e permissões efectivas de um utilizador (cliente, admin ou credor)")
    @RequiresPermission(Perm.RBAC_READ)
    @GetMapping("/subjects/{id}/access")
    public SubjectAccess subjectAccess(@PathVariable UUID id) { return rbac.access(id); }

    @Operation(summary = "Atribuir papel (o papel tem de ser do mesmo realm do utilizador)")
    @RequiresPermission(Perm.RBAC_MANAGE)
    @PostMapping("/subjects/{id}/roles")
    public SubjectAccess assignRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody RoleChangeRequest r) {
        return rbac.assign(uid(jwt), id, r.role(), r.reason());
    }

    @Operation(summary = "Revogar papel (produz efeito imediato)")
    @RequiresPermission(Perm.RBAC_MANAGE)
    @PostMapping("/subjects/{id}/roles/revoke")
    public SubjectAccess revokeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody RoleChangeRequest r) {
        return rbac.revoke(uid(jwt), id, r.role(), r.reason());
    }

    @Operation(summary = "Histórico de conciliações")
    @RequiresPermission(Perm.RECON_READ)
    @GetMapping("/reconciliation/runs")
    public PageView<ReconciliationRunView> runs(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") @Min(0) int page,
                                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return admin.reconciliationRuns(uid(jwt), page, size);
    }

    @Operation(summary = "Detalhe de uma conciliação (divergências incluídas)")
    @RequiresPermission(Perm.RECON_READ)
    @GetMapping("/reconciliation/runs/{id}")
    public ReconciliationDetail run(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return admin.reconciliationRun(uid(jwt), id); }

    @Operation(summary = "Pedir conciliação manual de um dia (entra na fila)")
    @RequiresPermission(Perm.RECON_RUN)
    @PostMapping("/reconciliation/run")
    public ResponseEntity<Map<String, Object>> runNow(@AuthenticationPrincipal Jwt jwt, @RequestParam LocalDate date) {
        if (jobs == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "JOBS_DISABLED", "Jobs desactivados.");
        if (date.isAfter(LocalDate.now(java.time.ZoneId.of("Africa/Luanda")))) throw ApiException.badRequest("FUTURE_DATE", "A data não pode ser futura.");
        jobs.enqueueReconciliation(date, "ADMIN:" + uid(jwt));
        admin.auditManualReconciliation(uid(jwt), date);
        return ResponseEntity.accepted().body(Map.of("status", "QUEUED", "businessDate", date.toString()));
    }

    @Operation(summary = "Estado da fila")
    @RequiresPermission(Perm.QUEUE_READ)
    @GetMapping("/queue/stats")
    public QueueStats queueStats(@AuthenticationPrincipal Jwt jwt) { return admin.queueStats(uid(jwt)); }

    @Operation(summary = "Mensagens em dead-letter")
    @RequiresPermission(Perm.QUEUE_READ)
    @GetMapping("/queue/dead")
    public List<QueueMessageView> dead(@AuthenticationPrincipal Jwt jwt) { return admin.deadLetters(uid(jwt)); }

    @Operation(summary = "Reenfileirar mensagem dead-letter")
    @RequiresPermission(Perm.QUEUE_REQUEUE)
    @PostMapping("/queue/{id}/requeue")
    public ResponseEntity<Void> requeue(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        admin.requeue(uid(jwt), id, r.reason());
        return ResponseEntity.noContent().build();
    }

    private static UUID uid(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
