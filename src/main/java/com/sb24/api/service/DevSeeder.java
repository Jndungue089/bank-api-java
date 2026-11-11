package com.sb24.api.service;

import com.sb24.api.domain.Account;
import com.sb24.api.domain.AdminRole;
import com.sb24.api.domain.AppUser;
import com.sb24.api.domain.Role;
import com.sb24.api.queue.QueueWorker;
import com.sb24.api.repo.AdminUserRepository;
import com.sb24.api.repo.UserRepository;
import com.sb24.api.web.Dtos.PaymentRequest;
import com.sb24.api.web.Dtos.TransferRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeder 4/4 (dev profile only): demo back-office staff, customers with opening deposits and a little history
 * (transfers, payments, one pending interbank transfer settled through the queue), so Swagger has data to show.
 */
@Component
@Profile("dev")
@Order(4)
public class DevSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevSeeder.class);
    static final String CUSTOMER_PW = "Demo#Passw0rd2026";
    static final String ADMIN_PW = "Admin#Passw0rd2026";
    static final String PARTNER_SECRET = "Creditor#Secret2026-demo-only";

    private final UserRepository users;
    private final AdminUserRepository admins;
    private final AuthService auth;
    private final AdminService adminService;
    private final AccountService accounts;
    private final TransferService transfers;
    private final PaymentService payments;
    private final QueueWorker worker;
    private final BeneficiaryService beneficiaries;
    private final SavingsService savings;
    private final DirectDebitService directDebit;
    private final com.sb24.api.repo.PartnerRepository partners;

    public DevSeeder(UserRepository users, AdminUserRepository admins, AuthService auth, AdminService adminService,
                     AccountService accounts, TransferService transfers, PaymentService payments, QueueWorker worker,
                     BeneficiaryService beneficiaries, SavingsService savings, DirectDebitService directDebit,
                     com.sb24.api.repo.PartnerRepository partners) {
        this.beneficiaries = beneficiaries;
        this.savings = savings;
        this.directDebit = directDebit;
        this.partners = partners;
        this.users = users;
        this.admins = admins;
        this.auth = auth;
        this.adminService = adminService;
        this.accounts = accounts;
        this.transfers = transfers;
        this.payments = payments;
        this.worker = worker;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (admins.count() == 0) {
            adminService.newAdmin("Admin Principal", "admin@example.ao", ADMIN_PW, AdminRole.SUPER_ADMIN, null);
            adminService.newAdmin("Operador Demo", "operador@example.ao", ADMIN_PW, AdminRole.OPERATOR, null);
            adminService.newAdmin("Auditor Demo", "auditor@example.ao", ADMIN_PW, AdminRole.AUDITOR, null);
            log.warn("DEV: admins admin@/operador@/auditor@example.ao (password {})", ADMIN_PW);
        }
        if (partners.findByClientId("epal-demo").isEmpty()) {
            adminService.newPartner("EPAL", "EPAL - Empresa Pública de Águas", "epal-demo", PARTNER_SECRET, null);
            adminService.newPartner("ENDE", "ENDE - Electricidade", "ende-demo", PARTNER_SECRET, null);
            log.warn("DEV: creditors epal-demo / ende-demo (secret {})", PARTNER_SECRET);
        }
        if (users.findByEmail("ana@example.ao").isPresent()) return;

        Account ana = customer("Ana Silva", "ana@example.ao", "923000001", "000000001LA001", "1000000.00");
        Account bruno = customer("Bruno Costa", "bruno@example.ao", "923000002", "000000002LA002", "250000.00");
        Account carla = customer("Carla Mendes", "carla@example.ao", "923000003", "000000003LA003", "75000.00");
        customer("Diogo Neto", "diogo@example.ao", "923000004", "000000004LA004", "0");

        UUID anaId = ana.getOwnerId();
        transfers.transfer(anaId, new TransferRequest(ana.getId(), bruno.getIban(), new BigDecimal("15000.00"), "Renda de Novembro"), key(), null);
        transfers.transfer(anaId, new TransferRequest(ana.getId(), carla.getIban(), new BigDecimal("2500.50"), "Almoço"), key(), null);
        payments.pay(anaId, new PaymentRequest(ana.getId(), "UNITEL", "923000001", new BigDecimal("1000.00")), key(), null);
        payments.pay(bruno.getOwnerId(), new PaymentRequest(bruno.getId(), "EPAL", "12345678", new BigDecimal("4300.00")), key(), null);
        String external = com.sb24.api.util.IbanUtil.generate("0040", "0001", "12345678901");
        transfers.transfer(anaId, new TransferRequest(ana.getId(), external, new BigDecimal("50000.00"), "Para outro banco"), key(), null);
        beneficiaries.add(anaId, new com.sb24.api.web.Dtos.BeneficiaryRequest("Bruno", "Bruno Costa", bruno.getIban()));
        savings.subscribe(anaId, new com.sb24.api.web.Dtos.SubscribeSavingsRequest(ana.getId(), "FLEX", new BigDecimal("100000.00"), "Fundo de emergência"), key());
        savings.subscribe(anaId, new com.sb24.api.web.Dtos.SubscribeSavingsRequest(ana.getId(), "TERM90", new BigDecimal("200000.00"), "Viagem"), key());
        directDebit.create(anaId, new com.sb24.api.web.Dtos.MandateRequest(ana.getId(), "EPAL", "12345678", new BigDecimal("8000.00"),
                com.sb24.api.domain.DirectDebitMandate.Frequency.MONTHLY));
        worker.drain(); // settles the interbank transfer and delivers the notifications
        log.warn("DEV: customers ana@/bruno@/carla@/diogo@example.ao (password {}) with sample history", CUSTOMER_PW);
    }

    private Account customer(String name, String email, String phone, String bi, String opening) {
        AppUser u = auth.createUser(name, email, phone, bi, CUSTOMER_PW, Role.CUSTOMER);
        Account a = accounts.openCurrentAccount(u.getId());
        if (new BigDecimal(opening).signum() > 0) accounts.cashIn(a.getId(), new BigDecimal(opening), "Depósito inicial (simulação)");
        return a;
    }

    private static String key() { return "seed-" + UUID.randomUUID(); }
}
