package com.sb24.api.service;

import com.sb24.api.config.AppProperties;
import com.sb24.api.domain.AdminRole;
import com.sb24.api.repo.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeder 3/4 (all profiles): the first SUPER_ADMIN, only when the admin table is empty and the operator supplied
 * APP_ADMIN_BOOTSTRAP_EMAIL / APP_ADMIN_BOOTSTRAP_PASSWORD. The dev profile seeds demo admins in DevSeeder instead.
 */
@Component
@Order(3)
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private final AdminUserRepository admins;
    private final AdminService adminService;
    private final AuditService audit;
    private final AppProperties props;
    private final org.springframework.core.env.Environment env;

    public AdminBootstrap(AdminUserRepository admins, AdminService adminService, AuditService audit, AppProperties props,
                          org.springframework.core.env.Environment env) {
        this.admins = admins;
        this.adminService = adminService;
        this.audit = audit;
        this.props = props;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (admins.count() > 0 || env.acceptsProfiles(org.springframework.core.env.Profiles.of("dev", "test"))) return;
        String email = props.admin().bootstrapEmail(), pw = props.admin().bootstrapPassword();
        if (email == null || email.isBlank() || pw == null || pw.length() < 14) {
            log.warn("No admin exists. Set APP_ADMIN_BOOTSTRAP_EMAIL and APP_ADMIN_BOOTSTRAP_PASSWORD (>= 14 chars) to create the first SUPER_ADMIN.");
            return;
        }
        var a = adminService.newAdmin("Super Admin", email.trim().toLowerCase(), pw, AdminRole.SUPER_ADMIN, null);
        audit.system("ADMIN_BOOTSTRAP", "SUCCESS", "ADMIN", a.getId().toString(), "first SUPER_ADMIN created from environment");
        log.warn("First SUPER_ADMIN created: {}. Remove APP_ADMIN_BOOTSTRAP_PASSWORD from the environment now.", email);
    }
}
