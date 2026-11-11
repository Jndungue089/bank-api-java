package com.sb24.api.service;

import com.sb24.api.domain.Account;
import com.sb24.api.domain.LedgerEntry;
import com.sb24.api.repo.AccountRepository;
import com.sb24.api.repo.LedgerRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Detective control for integrity: re-derives every hash chain and the accounting invariants. */
@Service
public class LedgerIntegrityService {
    public record Report(boolean ok, int accountsChecked, long entriesChecked, BigDecimal totalBalance, List<String> violations) {}

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final LedgerService ledgerService;
    private final com.sb24.api.repo.AuditLogRepository auditLogs;
    private final AuditService auditService;

    public LedgerIntegrityService(AccountRepository accounts, LedgerRepository ledger, LedgerService ledgerService,
                                  com.sb24.api.repo.AuditLogRepository auditLogs, AuditService auditService) {
        this.auditLogs = auditLogs;
        this.auditService = auditService;
        this.accounts = accounts;
        this.ledger = ledger;
        this.ledgerService = ledgerService;
    }

    @Transactional(readOnly = true)
    public Report verify() {
        List<String> violations = new ArrayList<>();
        long entries = 0;
        List<Account> all = accounts.findAll();
        for (Account a : all) {
            List<LedgerEntry> chain = ledger.findByAccountIdOrderBySeqAsc(a.getId());
            String prev = null;
            long expectedSeq = 1;
            for (LedgerEntry e : chain) {
                entries++;
                if (e.getSeq() != expectedSeq) violations.add(a.getIban() + ": gap or reorder at seq " + e.getSeq() + " (expected " + expectedSeq + ")");
                if (!Objects.equals(e.getPrevHash(), prev)) violations.add(a.getIban() + ": broken chain at seq " + e.getSeq());
                if (!ledgerService.hashOf(e).equals(e.getHash())) violations.add(a.getIban() + ": tampered entry at seq " + e.getSeq());
                prev = e.getHash();
                expectedSeq = e.getSeq() + 1;
            }
            if (a.getLedgerSeq() != chain.size()) violations.add(a.getIban() + ": ledger length " + chain.size() + " != account counter " + a.getLedgerSeq());
            if (!Objects.equals(a.getLastHash(), prev)) violations.add(a.getIban() + ": chain head mismatch (entries removed or appended outside the app)");
            BigDecimal expected = chain.isEmpty() ? BigDecimal.ZERO : chain.get(chain.size() - 1).getBalanceAfter();
            if (a.getBalance().compareTo(expected) != 0) violations.add(a.getIban() + ": balance " + a.getBalance() + " != ledger " + expected);
        }
        for (com.sb24.api.domain.AuditLog row : auditLogs.findAll()) { // tamper evidence for the audit trail itself
            entries++;
            if (!auditService.mac(row).equals(row.getMac())) violations.add("audit_log row " + row.getId() + " was modified");
        }
        BigDecimal total = accounts.totalBalance();
        if (total.compareTo(BigDecimal.ZERO) != 0) violations.add("books out of balance: sum of all accounts is " + total);
        return new Report(violations.isEmpty(), all.size(), entries, total, violations);
    }
}
