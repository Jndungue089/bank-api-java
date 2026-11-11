package com.sb24.api.access;

/** The permission catalogue. A permission is the unit of authorisation: no operation exists without one. */
public final class Perm {
    private Perm() {}

    // customer realm
    public static final String PROFILE_READ = "PROFILE_READ";
    public static final String ACCOUNT_READ = "ACCOUNT_READ";
    public static final String STATEMENT_READ = "STATEMENT_READ";
    public static final String TRANSFER_CREATE = "TRANSFER_CREATE";
    public static final String TRANSFER_READ = "TRANSFER_READ";
    public static final String PAYMENT_READ = "PAYMENT_READ";
    public static final String PAYMENT_CREATE = "PAYMENT_CREATE";
    public static final String NOTIFICATION_READ = "NOTIFICATION_READ";
    public static final String BENEFICIARY_READ = "BENEFICIARY_READ";
    public static final String BENEFICIARY_MANAGE = "BENEFICIARY_MANAGE";
    public static final String SAVINGS_READ = "SAVINGS_READ";
    public static final String SAVINGS_MANAGE = "SAVINGS_MANAGE";
    public static final String DIRECT_DEBIT_READ = "DIRECT_DEBIT_READ";
    public static final String DIRECT_DEBIT_MANAGE = "DIRECT_DEBIT_MANAGE";

    // admin realm
    public static final String AUDIT_READ = "AUDIT_READ";
    public static final String INTEGRITY_CHECK = "INTEGRITY_CHECK";
    public static final String CUSTOMER_LIST = "CUSTOMER_LIST";
    public static final String CUSTOMER_PII_READ = "CUSTOMER_PII_READ";
    public static final String CUSTOMER_BLOCK = "CUSTOMER_BLOCK";
    public static final String ACCOUNT_FREEZE = "ACCOUNT_FREEZE";
    public static final String ADMIN_READ = "ADMIN_READ";
    public static final String ADMIN_MANAGE = "ADMIN_MANAGE";
    public static final String RECON_READ = "RECON_READ";
    public static final String RECON_RUN = "RECON_RUN";
    public static final String QUEUE_READ = "QUEUE_READ";
    public static final String QUEUE_REQUEUE = "QUEUE_REQUEUE";
    public static final String RBAC_READ = "RBAC_READ";
    public static final String RBAC_MANAGE = "RBAC_MANAGE";
    public static final String PARTNER_MANAGE = "PARTNER_MANAGE";

    // partner (creditor) realm
    public static final String COLLECTION_SUBMIT = "COLLECTION_SUBMIT";
    public static final String COLLECTION_READ = "COLLECTION_READ";
}
