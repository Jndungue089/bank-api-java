package com.sb24.api.service;

import com.sb24.api.domain.AccountType;
import com.sb24.api.domain.AppUser;
import com.sb24.api.domain.Role;
import com.sb24.api.repo.AccountRepository;
import com.sb24.api.repo.UserRepository;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Seeder 1/4 (all profiles): the internal SYSTEM user and settlement accounts CLEARING, PAYMENTS, FEES. Idempotent. */
@Component
@Order(1)
public class SystemBootstrap implements ApplicationRunner {
    static final UUID SYSTEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UserRepository users;
    private final AccountRepository accounts;
    private final AccountService accountService;

    public SystemBootstrap(UserRepository users, AccountRepository accounts, AccountService accountService) {
        this.users = users;
        this.accounts = accounts;
        this.accountService = accountService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!users.existsById(SYSTEM_ID)) {
            AppUser s = new AppUser();
            s.setId(SYSTEM_ID);
            s.setFullName("SYSTEM");
            s.setEmail("system@internal.invalid");
            s.setPhone("000000000");
            s.setNationalId("SYSTEM");
            s.setNationalIdIndex("SYSTEM"); // not a real blind index: can never collide with an HMAC hex string
            s.setPasswordHash("!");         // not a valid hash: no password can ever match
            s.setRole(Role.SYSTEM);
            s.setEnabled(false);
            users.save(s);
        }
        for (String purpose : new String[] {"CLEARING", "PAYMENTS", "FEES", "INTEREST"}) {
            if (accounts.findIdByPurpose(purpose).isEmpty()) accountService.create(SYSTEM_ID, AccountType.SETTLEMENT, purpose);
        }
    }
}
