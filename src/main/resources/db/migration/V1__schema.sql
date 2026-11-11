CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    full_name VARCHAR(400) NOT NULL,              -- AES-256-GCM ciphertext
    email VARCHAR(160) NOT NULL UNIQUE,
    phone VARCHAR(255) NOT NULL,                  -- AES-256-GCM ciphertext
    national_id VARCHAR(255) NOT NULL,            -- AES-256-GCM ciphertext
    national_id_index VARCHAR(64) NOT NULL UNIQUE, -- HMAC-SHA256 blind index (uniqueness without storing plaintext)
    password_hash VARCHAR(255) NOT NULL,          -- Argon2id
    role VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP WITH TIME ZONE,
    blocked_by UUID,                               -- admin who blocked (separation-of-duties input)
    blocked_reason VARCHAR(200),
    blocked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE account (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    iban VARCHAR(25) NOT NULL UNIQUE,
    purpose VARCHAR(16),
    type VARCHAR(16) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance NUMERIC(19,2) NOT NULL,                -- saldo contabilístico (ledger / book balance)
    held NUMERIC(19,2) NOT NULL DEFAULT 0,         -- cativo: reservas activas; disponível = balance - held
    status VARCHAR(16) NOT NULL,
    ledger_seq BIGINT NOT NULL DEFAULT 0,
    last_hash VARCHAR(64),
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_held_non_negative CHECK (held >= 0),
    CONSTRAINT chk_available_non_negative CHECK (balance - held >= 0 OR type = 'SETTLEMENT')
);
CREATE INDEX idx_account_owner ON account(owner_id);

-- Append-only, hash-chained ledger: each row's HMAC covers its content and the previous row's hash (integrity).
CREATE TABLE ledger_entry (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id),
    seq BIGINT NOT NULL,
    transfer_ref UUID NOT NULL,
    kind VARCHAR(20) NOT NULL,
    direction VARCHAR(6) NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    balance_after NUMERIC(19,2) NOT NULL,
    counterparty_iban VARCHAR(25),
    description VARCHAR(140),
    prev_hash VARCHAR(64),
    hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_ledger_seq UNIQUE (account_id, seq)
);
CREATE INDEX idx_ledger_account_date ON ledger_entry(account_id, created_at DESC);
CREATE INDEX idx_ledger_ref ON ledger_entry(transfer_ref);

CREATE TABLE refresh_token (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    family_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used BOOLEAN NOT NULL,
    revoked BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_refresh_family ON refresh_token(family_id);
CREATE INDEX idx_refresh_expiry ON refresh_token(expires_at);

CREATE TABLE idempotency_record (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    idem_key VARCHAR(80) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_idem UNIQUE (user_id, idem_key)
);
CREATE INDEX idx_idem_created ON idempotency_record(created_at);

-- Separate identity store for back-office staff: admins are never rows of app_user.
CREATE TABLE admin_user (
    id UUID PRIMARY KEY,
    email VARCHAR(160) NOT NULL UNIQUE,
    full_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP WITH TIME ZONE,
    created_by UUID,
    last_login_at TIMESTAMP WITH TIME ZONE,
    last_login_ip VARCHAR(45),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- One row per action by anyone (customer, admin, system, anonymous). Each row carries an HMAC (tamper evidence).
CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    actor_type VARCHAR(10) NOT NULL,
    actor_id UUID,
    action VARCHAR(50) NOT NULL,
    resource_type VARCHAR(30),
    resource_id VARCHAR(64),
    outcome VARCHAR(12) NOT NULL,
    http_method VARCHAR(8),
    path VARCHAR(200),
    http_status INT,
    ip VARCHAR(45),
    device_id VARCHAR(64),
    user_agent VARCHAR(255),
    request_id VARCHAR(64),
    detail VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    mac VARCHAR(64) NOT NULL
);
CREATE INDEX idx_audit_actor ON audit_log(actor_id, created_at DESC);
CREATE INDEX idx_audit_date ON audit_log(created_at DESC);
CREATE INDEX idx_audit_action ON audit_log(action, created_at DESC);

CREATE TABLE known_device (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    device_key VARCHAR(64) NOT NULL,
    user_agent VARCHAR(255),
    first_ip VARCHAR(45),
    first_seen TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_device UNIQUE (user_id, device_key)
);

CREATE TABLE payment_provider (
    code VARCHAR(12) PRIMARY KEY,
    name VARCHAR(60) NOT NULL,
    reference_pattern VARCHAR(60) NOT NULL,
    reference_hint VARCHAR(80) NOT NULL,
    direct_debit BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL
);

-- Reservations ("cativos"): reduce the available balance now, hit the ledger only at settlement.
CREATE TABLE account_hold (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id),
    ref UUID NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    status VARCHAR(10) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_hold_account ON account_hold(account_id, status);
CREATE INDEX idx_hold_ref ON account_hold(ref);

CREATE TABLE pending_transfer (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    account_id UUID NOT NULL REFERENCES account(id),
    to_iban VARCHAR(25) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    fee NUMERIC(19,2) NOT NULL,
    description VARCHAR(140),
    status VARCHAR(12) NOT NULL,
    reason VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    settled_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_pending_status ON pending_transfer(status, created_at);
CREATE INDEX idx_pending_user ON pending_transfer(user_id, created_at DESC);

-- Durable queue (transactional outbox): enqueued in the same DB transaction as the business change.
CREATE TABLE queue_message (
    id UUID PRIMARY KEY,
    queue VARCHAR(30) NOT NULL,
    payload TEXT NOT NULL,
    dedup_key VARCHAR(80) UNIQUE,
    status VARCHAR(12) NOT NULL,
    attempts INT NOT NULL,
    max_attempts INT NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by VARCHAR(60),
    locked_until TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(300),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_queue_ready ON queue_message(status, next_attempt_at);

CREATE TABLE notification (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    channel VARCHAR(8) NOT NULL,
    message VARCHAR(300) NOT NULL,
    status VARCHAR(8) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_notification_user ON notification(user_id, created_at DESC);

CREATE TABLE reconciliation_run (
    id UUID PRIMARY KEY,
    business_date DATE NOT NULL,
    triggered_by VARCHAR(60) NOT NULL,
    status VARCHAR(16) NOT NULL,
    accounts_checked INT NOT NULL,
    entries_checked BIGINT NOT NULL,
    total_debits NUMERIC(19,2) NOT NULL,
    total_credits NUMERIC(19,2) NOT NULL,
    discrepancies INT NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_recon_date ON reconciliation_run(business_date DESC);

CREATE TABLE reconciliation_item (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES reconciliation_run(id),
    severity VARCHAR(8) NOT NULL,
    type VARCHAR(30) NOT NULL,
    account_id UUID,
    expected NUMERIC(19,2),
    actual NUMERIC(19,2),
    detail VARCHAR(300)
);
CREATE INDEX idx_recon_item_run ON reconciliation_item(run_id);

CREATE TABLE daily_snapshot (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id),
    business_date DATE NOT NULL,
    opening_balance NUMERIC(19,2) NOT NULL,
    total_debits NUMERIC(19,2) NOT NULL,
    total_credits NUMERIC(19,2) NOT NULL,
    closing_balance NUMERIC(19,2) NOT NULL,
    entries INT NOT NULL,
    CONSTRAINT uq_snapshot UNIQUE (account_id, business_date)
);

-- ===================== Access control: RBAC (who may do what) =====================
CREATE TABLE permission (
    code VARCHAR(40) PRIMARY KEY,
    description VARCHAR(160) NOT NULL
);

CREATE TABLE access_role (
    code VARCHAR(30) PRIMARY KEY,
    realm VARCHAR(10) NOT NULL,            -- CUSTOMER | ADMIN | PARTNER: a role can only be given to a subject of its realm
    description VARCHAR(160) NOT NULL
);

CREATE TABLE role_permission (
    id UUID PRIMARY KEY,
    role_code VARCHAR(30) NOT NULL REFERENCES access_role(code),
    permission_code VARCHAR(40) NOT NULL REFERENCES permission(code),
    CONSTRAINT uq_role_permission UNIQUE (role_code, permission_code)
);

-- subject_id is an app_user, admin_user or partner id (the realm of the role says which).
CREATE TABLE subject_role (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL,
    role_code VARCHAR(30) NOT NULL REFERENCES access_role(code),
    assigned_by UUID,
    assigned_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_subject_role UNIQUE (subject_id, role_code)
);
CREATE INDEX idx_subject_role_subject ON subject_role(subject_id);

-- Third-party creditors (billers) that submit direct-debit collections through the partner API.
CREATE TABLE partner (
    id UUID PRIMARY KEY,
    creditor_code VARCHAR(12) NOT NULL REFERENCES payment_provider(code),
    name VARCHAR(80) NOT NULL,
    client_id VARCHAR(40) NOT NULL UNIQUE,
    secret_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- ===================== Beneficiaries =====================
CREATE TABLE beneficiary (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    alias VARCHAR(40) NOT NULL,
    name VARCHAR(400) NOT NULL,                  -- AES-256-GCM ciphertext
    iban VARCHAR(25) NOT NULL,
    bank_code VARCHAR(4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_beneficiary UNIQUE (user_id, iban)
);
CREATE INDEX idx_beneficiary_user ON beneficiary(user_id);

-- ===================== Savings =====================
CREATE TABLE savings_product (
    code VARCHAR(12) PRIMARY KEY,
    name VARCHAR(60) NOT NULL,
    annual_rate NUMERIC(7,4) NOT NULL,
    min_amount NUMERIC(19,2) NOT NULL,
    term_days INT NOT NULL,                      -- 0 = flexible (withdraw any time)
    enabled BOOLEAN NOT NULL
);

CREATE TABLE savings_plan (
    account_id UUID PRIMARY KEY REFERENCES account(id),
    user_id UUID NOT NULL REFERENCES app_user(id),
    product_code VARCHAR(12) NOT NULL REFERENCES savings_product(code),
    goal_name VARCHAR(60),
    annual_rate NUMERIC(7,4) NOT NULL,           -- rate frozen at subscription
    maturity_date DATE,
    status VARCHAR(10) NOT NULL,
    opened_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_savings_user ON savings_plan(user_id);

CREATE TABLE savings_interest (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES account(id),
    accrual_date DATE NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    CONSTRAINT uq_interest UNIQUE (account_id, accrual_date)   -- accrual is idempotent per account and day
);

-- ===================== Direct debit =====================
CREATE TABLE direct_debit_mandate (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    account_id UUID NOT NULL REFERENCES account(id),
    creditor_code VARCHAR(12) NOT NULL REFERENCES payment_provider(code),
    creditor_reference VARCHAR(20) NOT NULL,
    max_amount NUMERIC(19,2) NOT NULL,
    frequency VARCHAR(10) NOT NULL,
    status VARCHAR(10) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_mandate UNIQUE (creditor_code, creditor_reference)
);
CREATE INDEX idx_mandate_user ON direct_debit_mandate(user_id);

CREATE TABLE direct_debit_collection (
    id UUID PRIMARY KEY,
    mandate_id UUID NOT NULL REFERENCES direct_debit_mandate(id),
    partner_id UUID NOT NULL REFERENCES partner(id),
    external_ref VARCHAR(40) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    due_date DATE NOT NULL,
    status VARCHAR(10) NOT NULL,
    reason VARCHAR(60),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_collection UNIQUE (partner_id, external_ref)
);
CREATE INDEX idx_collection_mandate ON direct_debit_collection(mandate_id, status);
