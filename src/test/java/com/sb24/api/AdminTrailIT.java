package com.sb24.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sb24.api.domain.AdminRole;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class AdminTrailIT extends BaseIT {
    @Autowired com.sb24.api.service.AccountService accounts;

    private Map<String, Object> row(String sql, Object... args) { return db.queryForMap(sql, args); }

    @Test
    void customerActionsRecordIpDeviceAndUserAgent() throws Exception {
        Client c = registerAndLogin();
        String ip = newIp();
        mvc.perform(post("/api/v1/auth/login").with(from(ip)).header("X-Device-Id", "phone-ana-0001").header("User-Agent", "SB24-Android/5.1 (Pixel 8)")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", c.email(), "password", PW)))).andExpect(status().isOk());
        Map<String, Object> login = row("select * from audit_log where action = 'LOGIN' and outcome = 'SUCCESS' and ip = ? order by created_at desc limit 1", ip);
        assertEquals("CUSTOMER", login.get("ACTOR_TYPE"));
        assertEquals("phone-ana-0001", login.get("DEVICE_ID"));
        assertEquals("SB24-Android/5.1 (Pixel 8)", login.get("USER_AGENT"));
        assertNotNull(login.get("REQUEST_ID"));
        assertNotNull(login.get("MAC"));

        // the generic request trail: who called what, from where, with which result
        mvc.perform(get("/api/v1/accounts").with(from(ip)).header("Authorization", "Bearer " + c.access()).header("X-Device-Id", "phone-ana-0001"))
                .andExpect(status().isOk());
        Map<String, Object> req = row("select * from audit_log where action = 'HTTP_REQUEST' and path = '/api/v1/accounts' and ip = ? order by created_at desc limit 1", ip);
        assertEquals("CUSTOMER", req.get("ACTOR_TYPE"));
        assertEquals(subject(c.access()).toString(), req.get("ACTOR_ID").toString());
        assertEquals("GET", req.get("HTTP_METHOD"));
        assertEquals(200, req.get("HTTP_STATUS"));
    }

    @Test
    void failedAndAnonymousCallsAreTracedToo() throws Exception {
        String ip = newIp();
        mvc.perform(post("/api/v1/auth/login").with(from(ip)).header("User-Agent", "A".repeat(400)) // control characters never get this far: the Spring Security firewall answers 400
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", "ghost@example.ao", "password", "Wrong#Passw0rd!!"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/accounts").with(from(ip))).andExpect(status().isUnauthorized());
        assertEquals(1, db.queryForObject("select count(*) from audit_log where action = 'LOGIN' and outcome = 'FAILED' and actor_type = 'ANONYMOUS' and ip = ?", Integer.class, ip));
        assertEquals(1, db.queryForObject("select count(*) from audit_log where action = 'HTTP_REQUEST' and outcome = 'DENIED' and http_status = 401 and path = '/api/v1/accounts' and ip = ?", Integer.class, ip));
        String ua = db.queryForObject("select user_agent from audit_log where action = 'LOGIN' and ip = ?", String.class, ip);
        assertEquals(255, ua.length(), "user agent is truncated, not trusted to be short");
    }

    @Test
    void malformedDeviceIdIsDroppedNotStored() throws Exception {
        Client c = registerAndLogin();
        String ip = newIp();
        mvc.perform(get("/api/v1/accounts").with(from(ip)).header("Authorization", "Bearer " + c.access()).header("X-Device-Id", "<script>x</script>"))
                .andExpect(status().isOk());
        assertNull(row("select device_id from audit_log where action = 'HTTP_REQUEST' and ip = ? limit 1", ip).get("DEVICE_ID"));
    }

    @Test
    void loginFromANewDeviceIsAuditedAndNotified() throws Exception {
        Client c = registerAndLogin();  // device #1: derived from user agent (none in tests)
        String ip = newIp();
        mvc.perform(post("/api/v1/auth/login").with(from(ip)).header("X-Device-Id", "tablet-new-0002")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", c.email(), "password", PW)))).andExpect(status().isOk());
        assertEquals(1, db.queryForObject("select count(*) from audit_log where action = 'NEW_DEVICE_LOGIN' and ip = ?", Integer.class, ip));
        queueWorker.drain();
        assertTrue(getJson(c.access(), c.ip(), "/api/v1/notifications").toString().contains("Novo dispositivo"));
    }

    @Test
    void adminsHaveTheirOwnRealmAndCannotUseCustomerEndpoints() throws Exception {
        Admin admin = newAdmin(AdminRole.SUPER_ADMIN);
        Client customer = registerAndLogin();
        mvc.perform(get("/api/v1/accounts").with(from(admin.ip())).header("Authorization", "Bearer " + admin.token())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit").with(from(customer.ip())).header("Authorization", "Bearer " + customer.access())).andExpect(status().isForbidden());
        // the same credentials are not valid on the other side's login
        mvc.perform(post("/api/v1/auth/login").with(from(newIp())).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", admin.email(), "password", ADMIN_PW)))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/auth/login").with(from(newIp())).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", customer.email(), "password", PW)))).andExpect(status().isUnauthorized());
        assertEquals(1, db.queryForObject("select count(*) from audit_log where action = 'ADMIN_LOGIN' and outcome = 'SUCCESS' and actor_id = ? and actor_type = 'ADMIN'", Integer.class, admin.id()));
    }

    @Test
    void adminRolesAreEnforced() throws Exception {
        Admin auditor = newAdmin(AdminRole.AUDITOR), op = newAdmin(AdminRole.OPERATOR), root = newAdmin(AdminRole.SUPER_ADMIN);
        String newAdmin = json.writeValueAsString(Map.of("fullName", "Novo Operador", "email", "novo" + UUID.randomUUID().toString().substring(0, 6) + "@example.ao",
                "password", ADMIN_PW, "role", "OPERATOR"));
        mvc.perform(get("/api/v1/admin/audit").with(from(auditor.ip())).header("Authorization", "Bearer " + auditor.token())).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/admins").with(from(op.ip())).header("Authorization", "Bearer " + op.token())
                .contentType(MediaType.APPLICATION_JSON).content(newAdmin)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/admins").with(from(root.ip())).header("Authorization", "Bearer " + root.token())
                .contentType(MediaType.APPLICATION_JSON).content(newAdmin)).andExpect(status().isCreated());
        assertEquals(1, db.queryForObject("select count(*) from audit_log where action = 'ADMIN_CREATE_ADMIN' and actor_id = ?", Integer.class, root.id()));
    }

    @Test
    void everyAdminActionIsTracedWithActorTargetReasonAndOrigin() throws Exception {
        Admin op = newAdmin(AdminRole.OPERATOR);
        Client victim = registerAndLogin();
        accounts.cashIn(victim.accountId(), new BigDecimal("500.00"), "test");
        UUID victimId = subject(victim.access());
        String auth = "Bearer " + op.token();

        // reading personal data is traced, and the data comes back masked
        String detail = mvc.perform(get("/api/v1/admin/customers/" + victimId).with(from(op.ip())).header("Authorization", auth)
                .header("X-Device-Id", "backoffice-pc-01").header("User-Agent", "Mozilla/5.0 BackOffice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phone").value("******222")).andReturn().getResponse().getContentAsString();
        assertFalse(detail.contains("923111222"));
        Map<String, Object> view = row("select * from audit_log where action = 'ADMIN_VIEW_CUSTOMER' and actor_id = ? order by created_at desc limit 1", op.id());
        assertEquals("ADMIN", view.get("ACTOR_TYPE"));
        assertEquals(victimId.toString(), view.get("RESOURCE_ID"));
        assertEquals("backoffice-pc-01", view.get("DEVICE_ID"));
        assertEquals("Mozilla/5.0 BackOffice", view.get("USER_AGENT"));
        assertEquals(op.ip(), view.get("IP"));

        // a reason is mandatory
        mvc.perform(post("/api/v1/admin/customers/" + victimId + "/block").with(from(op.ip())).header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        String reason = json.writeValueAsString(Map.of("reason", "suspeita de fraude ticket 1234"));
        mvc.perform(post("/api/v1/admin/customers/" + victimId + "/block").with(from(op.ip())).header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(reason)).andExpect(status().isNoContent());
        String detailText = db.queryForObject("select detail from audit_log where action = 'ADMIN_BLOCK_CUSTOMER' and actor_id = ? and resource_id = ?", String.class, op.id(), victimId.toString());
        assertTrue(detailText.contains("suspeita de fraude ticket 1234"));
        assertTrue(db.queryForObject("select count(*) from audit_log where action = 'HTTP_REQUEST' and actor_type = 'ADMIN' and actor_id = ? and path like '/api/v1/admin/customers/%/block'", Integer.class, op.id()) >= 1);

        // effect: the customer can no longer log in or move money
        mvc.perform(post("/api/v1/auth/login").with(from(newIp())).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", victim.email(), "password", PW)))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/transfers").with(from(victim.ip())).header("Authorization", "Bearer " + victim.access())
                .header("Idempotency-Key", "idem-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fromAccountId", victim.accountId(), "toIban", victim.iban().substring(0, 24) + "x", "amount", new BigDecimal("5.00")))))
                .andExpect(status().isBadRequest());
        // four-eyes: the operator who blocked cannot unblock; another operator can
        mvc.perform(post("/api/v1/admin/customers/" + victimId + "/unblock").with(from(op.ip())).header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(reason)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SEPARATION_OF_DUTIES"));
        Admin other = newAdmin(AdminRole.OPERATOR);
        mvc.perform(post("/api/v1/admin/customers/" + victimId + "/unblock").with(from(other.ip())).header("Authorization", "Bearer " + other.token())
                .contentType(MediaType.APPLICATION_JSON).content(reason)).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/login").with(from(newIp())).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", victim.email(), "password", PW)))).andExpect(status().isOk());

        // the audit trail is itself queryable by actor, and reading it is traced
        mvc.perform(get("/api/v1/admin/audit?actorId=" + op.id() + "&size=100").with(from(op.ip())).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.action=='ADMIN_BLOCK_CUSTOMER')]").exists());
        assertTrue(db.queryForObject("select count(*) from audit_log where action = 'ADMIN_VIEW_AUDIT' and actor_id = ?", Integer.class, op.id()) >= 1);
    }

    @Test
    void adminLockoutAndGenericErrors() throws Exception {
        Admin a = newAdmin(AdminRole.AUDITOR);
        String ip = newIp();
        String bad = json.writeValueAsString(Map.of("email", a.email(), "password", "Wrong#Passw0rd-2026"));
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/admin/auth/login").with(from(ip)).contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/admin/auth/login").with(from(ip)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", a.email(), "password", ADMIN_PW)))).andExpect(status().isUnauthorized());
        assertEquals(1, db.queryForObject("select count(*) from audit_log where action = 'ADMIN_ACCOUNT_LOCK' and actor_id = ?", Integer.class, a.id()));
    }
}
