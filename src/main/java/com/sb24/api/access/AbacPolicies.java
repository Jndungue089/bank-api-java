package com.sb24.api.access;

import com.sb24.api.access.AbacPolicy.Decision;
import com.sb24.api.audit.RequestContext;
import com.sb24.api.config.AppProperties;
import com.sb24.api.repo.KnownDeviceRepository;
import com.sb24.api.repo.PartnerRepository;
import com.sb24.api.util.Hashing;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The ABAC rule set. Each rule is small, named (it appears in the audit trail) and independently tested. */
public final class AbacPolicies {
    private AbacPolicies() {}

    /** Customers only touch their own resources. A foreign resource is reported as "not found" (no existence oracle). */
    @Component
    public static class OwnershipPolicy implements AbacPolicy {
        @Override
        public Optional<Decision> evaluate(Request r) {
            if (!"customer".equals(r.subject().realm()) || r.resource().ownerId() == null) return Optional.empty();
            return r.resource().ownerId().equals(r.subject().id()) ? Optional.empty() : Decision.denyAsNotFound("OwnershipPolicy", "NOT_OWNER");
        }
    }

    /** Money may only leave an ACTIVE account of the right type. */
    @Component
    public static class AccountUsablePolicy implements AbacPolicy {
        @Override
        public Optional<Decision> evaluate(Request r) {
            if (!"ACCOUNT".equals(r.resource().type()) || !Boolean.TRUE.equals(r.resource().attr("moneyOut"))) return Optional.empty();
            if (!"ACTIVE".equals(r.resource().attr("status"))) return Decision.deny("AccountUsablePolicy", "ACCOUNT_" + r.resource().attr("status"));
            Object required = r.resource().attr("requiredType");
            if (required != null && !required.equals(r.resource().attr("accountType"))) return Decision.deny("AccountUsablePolicy", "WRONG_ACCOUNT_TYPE");
            return Optional.empty();
        }
    }

    /** Anti-fraud cooling-off: a beneficiary added less than 24 h ago can only receive small transfers. */
    @Component
    public static class NewBeneficiaryCoolingOffPolicy implements AbacPolicy {
        private final AppProperties props;
        public NewBeneficiaryCoolingOffPolicy(AppProperties props) { this.props = props; }

        @Override
        public Optional<Decision> evaluate(Request r) {
            Object created = r.resource().attr("beneficiaryCreatedAt"), amount = r.resource().attr("amount");
            if (!Perm.TRANSFER_CREATE.equals(r.permission()) || !(created instanceof Instant) || !(amount instanceof BigDecimal)) return Optional.empty();
            boolean fresh = r.now().isBefore(((Instant) created).plus(props.banking().newBeneficiaryCoolingOff()));
            return fresh && ((BigDecimal) amount).compareTo(props.banking().newBeneficiaryLimit()) > 0
                    ? Decision.deny("NewBeneficiaryCoolingOffPolicy", "BENEFICIARY_COOLING_OFF") : Optional.empty();
        }
    }

    /** High-value outgoing money needs a device this customer has already logged in from (environment attribute). */
    @Component
    public static class HighValueDevicePolicy implements AbacPolicy {
        private final AppProperties props;
        private final KnownDeviceRepository devices;
        public HighValueDevicePolicy(AppProperties props, KnownDeviceRepository devices) { this.props = props; this.devices = devices; }

        @Override
        public Optional<Decision> evaluate(Request r) {
            Object amount = r.resource().attr("amount");
            if (!Boolean.TRUE.equals(r.resource().attr("moneyOut")) || Boolean.TRUE.equals(r.resource().attr("ownAccounts"))
                    || !(amount instanceof BigDecimal) || r.env() == null || !"customer".equals(r.subject().realm())) return Optional.empty();
            if (((BigDecimal) amount).compareTo(props.banking().highValueThreshold()) < 0) return Optional.empty();
            String basis = r.env().deviceId() != null ? "id:" + r.env().deviceId() : "ua:" + r.env().userAgent();
            return devices.findByUserIdAndDeviceKey(r.subject().id(), Hashing.sha256Hex(basis)).isPresent()
                    ? Optional.empty() : Decision.deny("HighValueDevicePolicy", "UNTRUSTED_DEVICE");
        }
    }

    /** Term deposits are locked until maturity. */
    @Component
    public static class SavingsLockPolicy implements AbacPolicy {
        @Override
        public Optional<Decision> evaluate(Request r) {
            Object maturity = r.resource().attr("maturityDate");
            if (!"WITHDRAW".equals(r.resource().attr("operation")) || !(maturity instanceof LocalDate)) return Optional.empty();
            LocalDate today = LocalDate.now(ZoneId.of("Africa/Luanda"));
            return today.isBefore((LocalDate) maturity) ? Decision.deny("SavingsLockPolicy", "SAVINGS_LOCKED") : Optional.empty();
        }
    }

    /** A creditor (partner) can only see and collect from mandates and collections that belong to it. */
    @Component
    public static class PartnerScopePolicy implements AbacPolicy {
        private final PartnerRepository partners;
        public PartnerScopePolicy(PartnerRepository partners) { this.partners = partners; }

        @Override
        public Optional<Decision> evaluate(Request r) {
            if (!"partner".equals(r.subject().realm())) return Optional.empty();
            Object creditor = r.resource().attr("creditorCode"), partnerId = r.resource().attr("partnerId");
            if (partnerId != null && !partnerId.equals(r.subject().id())) return Decision.denyAsNotFound("PartnerScopePolicy", "NOT_YOUR_COLLECTION");
            if (creditor != null) {
                String own = partners.findById(r.subject().id()).map(p -> p.getCreditorCode()).orElse("");
                if (!own.equals(creditor)) return Decision.denyAsNotFound("PartnerScopePolicy", "CREDITOR_MISMATCH");
            }
            return Optional.empty();
        }
    }

    /** A collection can never exceed what the customer authorised in the mandate. */
    @Component
    public static class MandateLimitPolicy implements AbacPolicy {
        @Override
        public Optional<Decision> evaluate(Request r) {
            Object amount = r.resource().attr("amount"), max = r.resource().attr("mandateMax");
            if (!Perm.COLLECTION_SUBMIT.equals(r.permission()) || !(amount instanceof BigDecimal) || !(max instanceof BigDecimal)) return Optional.empty();
            return ((BigDecimal) amount).compareTo((BigDecimal) max) > 0 ? Decision.deny("MandateLimitPolicy", "EXCEEDS_MANDATE_LIMIT") : Optional.empty();
        }
    }

    /** Four-eyes: an OPERATOR cannot undo their own block. SUPER_ADMIN is exempt. */
    @Component
    public static class SeparationOfDutiesPolicy implements AbacPolicy {
        private final PermissionService permissions;
        public SeparationOfDutiesPolicy(PermissionService permissions) { this.permissions = permissions; }

        @Override
        public Optional<Decision> evaluate(Request r) {
            if (!Perm.CUSTOMER_BLOCK.equals(r.permission()) || !"UNBLOCK".equals(r.resource().attr("operation"))) return Optional.empty();
            Object blockedBy = r.resource().attr("blockedBy");
            if (blockedBy == null || !blockedBy.equals(r.subject().id()) || permissions.rolesOf(r.subject().id()).contains(Rbac.SUPER_ADMIN)) return Optional.empty();
            return Decision.deny("SeparationOfDutiesPolicy", "SEPARATION_OF_DUTIES");
        }
    }

    /** Nobody edits their own roles (prevents self-escalation and accidental lock-out). */
    @Component
    public static class SelfModificationPolicy implements AbacPolicy {
        @Override
        public Optional<Decision> evaluate(Request r) {
            if (!Perm.RBAC_MANAGE.equals(r.permission())) return Optional.empty();
            Object target = r.resource().attr("targetId");
            return target instanceof UUID && target.equals(r.subject().id()) ? Decision.deny("SelfModificationPolicy", "SELF_MODIFICATION") : Optional.empty();
        }
    }
}
