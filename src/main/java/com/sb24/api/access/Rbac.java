package com.sb24.api.access;

import static com.sb24.api.access.Perm.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Default roles and their permissions. Seeded into the DB at start-up; the DB is the source of truth at run time. */
public final class Rbac {
    private Rbac() {}

    public static final String CUSTOMER = "CUSTOMER";
    public static final String CUSTOMER_VIEWER = "CUSTOMER_VIEWER";
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String OPERATOR = "OPERATOR";
    public static final String AUDITOR = "AUDITOR";
    public static final String CREDITOR = "CREDITOR";

    public record RoleDef(String realm, String description, List<String> permissions) {}

    private static final List<String> CUSTOMER_READ = List.of(PROFILE_READ, ACCOUNT_READ, STATEMENT_READ, TRANSFER_READ, PAYMENT_READ,
            NOTIFICATION_READ, BENEFICIARY_READ, SAVINGS_READ, DIRECT_DEBIT_READ);
    private static final List<String> ADMIN_ALL = List.of(AUDIT_READ, INTEGRITY_CHECK, CUSTOMER_LIST, CUSTOMER_PII_READ, CUSTOMER_BLOCK,
            ACCOUNT_FREEZE, ADMIN_READ, ADMIN_MANAGE, RECON_READ, RECON_RUN, QUEUE_READ, QUEUE_REQUEUE, RBAC_READ, RBAC_MANAGE, PARTNER_MANAGE);

    public static final Map<String, String> PERMISSIONS = new LinkedHashMap<>();
    public static final Map<String, RoleDef> ROLES = new LinkedHashMap<>();

    static {
        String[][] p = {
            {PROFILE_READ, "Ver o próprio perfil"}, {ACCOUNT_READ, "Ver as próprias contas e saldos"}, {STATEMENT_READ, "Ver extractos"},
            {TRANSFER_CREATE, "Ordenar transferências"}, {TRANSFER_READ, "Ver estado de transferências"}, {PAYMENT_READ, "Ver entidades de pagamento"},
            {PAYMENT_CREATE, "Pagar serviços"}, {NOTIFICATION_READ, "Ler notificações"}, {BENEFICIARY_READ, "Ver beneficiários"},
            {BENEFICIARY_MANAGE, "Criar/remover beneficiários"}, {SAVINGS_READ, "Ver poupanças"}, {SAVINGS_MANAGE, "Subscrever, depositar e levantar poupança"},
            {DIRECT_DEBIT_READ, "Ver mandatos de débito directo"}, {DIRECT_DEBIT_MANAGE, "Criar/pausar/cancelar mandatos de débito directo"},
            {AUDIT_READ, "Consultar o rasto de auditoria"}, {INTEGRITY_CHECK, "Verificar a integridade do razão e do rasto"},
            {CUSTOMER_LIST, "Listar clientes (mascarados)"}, {CUSTOMER_PII_READ, "Ver dados pessoais de um cliente"}, {CUSTOMER_BLOCK, "Bloquear/desbloquear clientes"},
            {ACCOUNT_FREEZE, "Congelar/descongelar contas"}, {ADMIN_READ, "Listar administradores"}, {ADMIN_MANAGE, "Criar administradores"},
            {RECON_READ, "Ver conciliações"}, {RECON_RUN, "Pedir conciliação manual"}, {QUEUE_READ, "Ver a fila e dead-letters"},
            {QUEUE_REQUEUE, "Reenfileirar mensagens"}, {RBAC_READ, "Ver papéis e permissões"}, {RBAC_MANAGE, "Atribuir/revogar papéis"},
            {PARTNER_MANAGE, "Gerir credores (parceiros de débito directo)"},
            {COLLECTION_SUBMIT, "Submeter cobranças de débito directo"}, {COLLECTION_READ, "Consultar as próprias cobranças"}};
        for (String[] e : p) PERMISSIONS.put(e[0], e[1]);

        ROLES.put(CUSTOMER, new RoleDef("CUSTOMER", "Cliente com acesso completo",
                List.of(PROFILE_READ, ACCOUNT_READ, STATEMENT_READ, TRANSFER_CREATE, TRANSFER_READ, PAYMENT_READ, PAYMENT_CREATE, NOTIFICATION_READ,
                        BENEFICIARY_READ, BENEFICIARY_MANAGE, SAVINGS_READ, SAVINGS_MANAGE, DIRECT_DEBIT_READ, DIRECT_DEBIT_MANAGE)));
        ROLES.put(CUSTOMER_VIEWER, new RoleDef("CUSTOMER", "Cliente só de consulta (sem movimentar dinheiro)", CUSTOMER_READ));
        ROLES.put(SUPER_ADMIN, new RoleDef("ADMIN", "Administrador com todas as permissões", ADMIN_ALL));
        ROLES.put(OPERATOR, new RoleDef("ADMIN", "Operador: bloqueios, congelamentos, conciliação e fila",
                List.of(CUSTOMER_LIST, CUSTOMER_PII_READ, CUSTOMER_BLOCK, ACCOUNT_FREEZE, AUDIT_READ, INTEGRITY_CHECK, RECON_READ, RECON_RUN, QUEUE_READ, QUEUE_REQUEUE, RBAC_READ)));
        ROLES.put(AUDITOR, new RoleDef("ADMIN", "Auditor: só leitura, sem dados pessoais",
                List.of(CUSTOMER_LIST, AUDIT_READ, INTEGRITY_CHECK, RECON_READ, QUEUE_READ, RBAC_READ)));
        ROLES.put(CREDITOR, new RoleDef("PARTNER", "Credor que submete cobranças de débito directo", List.of(COLLECTION_SUBMIT, COLLECTION_READ)));
    }
}
