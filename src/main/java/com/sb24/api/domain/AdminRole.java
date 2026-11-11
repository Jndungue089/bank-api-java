package com.sb24.api.domain;

/** SUPER_ADMIN: everything incl. creating admins. OPERATOR: blocks/freezes, reconciliation, queue. AUDITOR: read-only. */
public enum AdminRole { SUPER_ADMIN, OPERATOR, AUDITOR }
