package com.sb24.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sb24.api.access.PermissionService;
import com.sb24.api.domain.AdminRole;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** RBAC: nothing happens without a permission looked up in the DB. ABAC: context rules on top. */
class RbacAbacIT extends BaseIT {
    @Autowired com.sb24.api.service.AccountService accounts;
    @Autowired com.sb24.api.service.RbacService rbac;
    @Autowired PermissionService permissions;

    private String body(Client from, String to, String amount) throws Exception {
        return json.writeValueAsString(Map.of("fromAccountId", from.accountId(), "toIban", to, "amount", new BigDecimal(amount)));
    }

    private void transferExpect(Client c, String json, int status, String deviceId) throws Exception {
        var req = post("/api/v1/transfers").with(from(c.ip())).header("Authorization", "Bearer " + c.access()).header("Idempotency-Key", "idem-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (deviceId != null) req = req.header("X-Device-Id", deviceId);
        mvc.perform(req).andExpect(status().is(status));
    }

    @Test
    void handlersWithoutADeclaredPermissionAreRefused() throws Exception {
        Client c = registerAndLogin();
        mvc.perform(get("/api/v1/accounts/test-forgotten").with(from(c.ip())).header("Authorization", "Bearer " + c.access()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_POLICY_DEFINED"));
        mvc.perform(get("/api/v1/accounts/test-forgotten").with(from(newIp()))).andExpect(status().isUnauthorized());   // anonymous: stopped earlier
        mvc.perform(get("/api/v1/never-declared").with(from(c.ip())).header("Authorization", "Bearer " + c.access())).andExpect(status().isForbidden()); // unknown path: denyAll
    }

    @Test
    void viewerRoleReadsButCannotMoveMoney() throws Exception {
        Client c = registerAndLogin(), other = registerAndLogin();
        UUID id = subject(c.access());
        accounts.cashIn(c.accountId(), new BigDecimal("1000.00"), "test");
        rbac.grant(id, "CUSTOMER_VIEWER", null);
        // CUSTOMER_VIEWER is additive here; remove the full role so only the viewer role remains
        Admin root = newAdmin(AdminRole.SUPER_ADMIN);
        mvc.perform(post("/api/v1/admin/subjects/" + id + "/roles/revoke").with(from(root.ip())).header("Authorization", "Bearer " + root.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("role", "CUSTOMER", "reason", "passa a só consulta")))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/accounts").with(from(c.ip())).header("Authorization", "Bearer " + c.access())).andExpect(status().isOk());   // same token, new rights: effective at once
        transferExpect(c, body(c, other.iban(), "10.00"), 403, null);
        mvc.perform(post("/api/v1/beneficiaries").with(from(c.ip())).header("Authorization", "Bearer " + c.access()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("alias", "X", "name", "Fulano Tal", "iban", other.iban())))).andExpect(status().isForbidden());
        var denied = db.queryForMap("select * from audit_log where action = 'ACCESS_DENIED' and actor_id = ? order by created_at desc limit 1", id);
        assertEquals("CUSTOMER", denied.get("ACTOR_TYPE"));
        assertTrue(denied.get("DETAIL").toString().contains("permission=BENEFICIARY_MANAGE"));
        assertTrue(denied.get("DETAIL").toString().contains("reason=MISSING_PERMISSION"));
    }

    @Test
    void revokingTheRoleLocksTheCustomerOutImmediately() throws Exception {
        Client c = registerAndLogin();
        Admin root = newAdmin(AdminRole.SUPER_ADMIN);
        mvc.perform(get("/api/v1/accounts").with(from(c.ip())).header("Authorization", "Bearer " + c.access())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/subjects/" + subject(c.access()) + "/roles/revoke").with(from(root.ip())).header("Authorization", "Bearer " + root.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("role", "CUSTOMER", "reason", "conta suspensa por compliance")))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/accounts").with(from(c.ip())).header("Authorization", "Bearer " + c.access())).andExpect(status().isForbidden());
        assertEquals(1, db.queryForObject("select count(*) from audit_log where action = 'ADMIN_REVOKE_ROLE' and actor_id = ?", Integer.class, root.id()));
    }

    @Test
    void roleAdministrationIsItselfPermissionedAndRealmChecked() throws Exception {
        Admin op = newAdmin(AdminRole.OPERATOR), root = newAdmin(AdminRole.SUPER_ADMIN), auditor = newAdmin(AdminRole.AUDITOR);
        Client c = registerAndLogin();
        UUID cid = subject(c.access());
        String ask = json.writeValueAsString(Map.of("role", "CUSTOMER_VIEWER", "reason", "teste de papéis"));
        mvc.perform(post("/api/v1/admin/subjects/" + cid + "/roles").with(from(op.ip())).header("Authorization", "Bearer " + op.token())
                .contentType(MediaType.APPLICATION_JSON).content(ask)).andExpect(status().isForbidden());                               // OPERATOR lacks RBAC_MANAGE
        mvc.perform(get("/api/v1/admin/rbac").with(from(auditor.ip())).header("Authorization", "Bearer " + auditor.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(6)));
        mvc.perform(post("/api/v1/admin/subjects/" + cid + "/roles").with(from(root.ip())).header("Authorization", "Bearer " + root.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("role", "SUPER_ADMIN", "reason", "escalada indevida"))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("REALM_MISMATCH"));                   // admin role to a customer
        mvc.perform(post("/api/v1/admin/subjects/" + root.id() + "/roles/revoke").with(from(root.ip())).header("Authorization", "Bearer " + root.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("role", "SUPER_ADMIN", "reason", "auto-remoção"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SELF_MODIFICATION"));
        mvc.perform(get("/api/v1/admin/subjects/" + cid + "/access").with(from(auditor.ip())).header("Authorization", "Bearer " + auditor.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.permissions[?(@=='TRANSFER_CREATE')]").exists());
    }

    @Test
    void auditorCannotReadPersonalDataButOperatorCan() throws Exception {
        Admin auditor = newAdmin(AdminRole.AUDITOR), op = newAdmin(AdminRole.OPERATOR);
        Client c = registerAndLogin();
        String url = "/api/v1/admin/customers/" + subject(c.access());
        mvc.perform(get(url).with(from(auditor.ip())).header("Authorization", "Bearer " + auditor.token())).andExpect(status().isForbidden());
        mvc.perform(get(url).with(from(op.ip())).header("Authorization", "Bearer " + op.token())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/customers").with(from(auditor.ip())).header("Authorization", "Bearer " + auditor.token())).andExpect(status().isOk());
    }

    @Test
    void abacCoolingOffLimitsTransfersToANewBeneficiary() throws Exception {
        Client a = registerAndLogin(), b = registerAndLogin();
        accounts.cashIn(a.accountId(), new BigDecimal("400000.00"), "test");
        String added = mvc.perform(post("/api/v1/beneficiaries").with(from(a.ip())).header("Authorization", "Bearer " + a.access()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("alias", "Maria", "name", "Maria Dos Santos", "iban", b.iban()))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.holderHint").exists()).andReturn().getResponse().getContentAsString();
        String bid = json.readTree(added).get("id").asText();
        String big = json.writeValueAsString(Map.of("fromAccountId", a.accountId(), "beneficiaryId", bid, "amount", new BigDecimal("150000.00")));
        String small = json.writeValueAsString(Map.of("fromAccountId", a.accountId(), "beneficiaryId", bid, "amount", new BigDecimal("50000.00")));
        mvc.perform(post("/api/v1/transfers").with(from(a.ip())).header("Authorization", "Bearer " + a.access()).header("Idempotency-Key", "idem-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(big)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BENEFICIARY_COOLING_OFF"));
        mvc.perform(post("/api/v1/transfers").with(from(a.ip())).header("Authorization", "Bearer " + a.access()).header("Idempotency-Key", "idem-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(small)).andExpect(status().isCreated());
        // after the cooling-off period the same transfer is allowed
        db.update("update beneficiary set created_at = created_at - interval '2' day where id = ?", UUID.fromString(bid));
        mvc.perform(post("/api/v1/transfers").with(from(a.ip())).header("Authorization", "Bearer " + a.access()).header("Idempotency-Key", "idem-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(big)).andExpect(status().isCreated());
        assertTrue(db.queryForObject("select count(*) from audit_log where action = 'ACCESS_DENIED' and detail like '%BENEFICIARY_COOLING_OFF%' and actor_id = ?", Integer.class, subject(a.access())) >= 1);
    }

    @Test
    void abacHighValueTransfersNeedATrustedDevice() throws Exception {
        Client a = registerAndLogin(), b = registerAndLogin();
        accounts.cashIn(a.accountId(), new BigDecimal("1500000.00"), "test");
        String ip = newIp();
        mvc.perform(post("/api/v1/auth/login").with(from(ip)).header("X-Device-Id", "trusted-phone-01").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", a.email(), "password", PW)))).andExpect(status().isOk());
        String hv = body(a, b.iban(), "600000.00");
        transferExpect(a, hv, 403, "stolen-device-99");   // valid token, but from a device never used to log in
        transferExpect(a, hv, 201, "trusted-phone-01");   // same customer, device seen at login
    }

    @Test
    void ownershipViolationsAreTracedEvenThoughTheyLookLikeNotFound() throws Exception {
        Client victim = registerAndLogin(), attacker = registerAndLogin();
        mvc.perform(get("/api/v1/accounts/" + victim.accountId()).with(from(attacker.ip())).header("Authorization", "Bearer " + attacker.access())).andExpect(status().isNotFound());
        var row = db.queryForMap("select * from audit_log where action = 'ACCESS_DENIED' and actor_id = ? order by created_at desc limit 1", subject(attacker.access()));
        assertTrue(row.get("DETAIL").toString().contains("policy=OwnershipPolicy"));
        assertEquals(victim.accountId().toString(), row.get("RESOURCE_ID"));
    }
}
