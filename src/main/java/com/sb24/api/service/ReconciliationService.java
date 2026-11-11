package com.sb24.api.service;

import com.sb24.api.domain.*;
import com.sb24.api.repo.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-of-day reconciliation of one business day in Angola time (Africa/Luanda). Every customer-visible number is
 * re-derived from the ledger and compared:
 *   1. per account: opening (previous snapshot) + credits - debits = closing balance in the ledger;
 *   2. every transaction (transfer_ref) has debits == credits (double entry);
 *   3. the sum of all closing balances is zero (nothing created or destroyed);
 *   4. held amounts on accounts == sum of ACTIVE holds; no interbank transfer stuck in PENDING > 24h;
 *      every transfer settled that day has ledger entries;
 *   5. the queue has no dead letters.
 * It also writes the day's snapshot per account, which becomes tomorrow's opening balance.
 */
@Service
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    static final ZoneId LUANDA = ZoneId.of("Africa/Luanda");

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final AccountHoldRepository holds;
    private final PendingTransferRepository pendings;
    private final QueueMessageRepository queueMessages;
    private final DailySnapshotRepository snapshots;
    private final ReconciliationRunRepository runs;
    private final ReconciliationItemRepository items;
    private final AuditService audit;

    public ReconciliationService(AccountRepository accounts, LedgerRepository ledger, AccountHoldRepository holds,
                                 PendingTransferRepository pendings, QueueMessageRepository queueMessages,
                                 DailySnapshotRepository snapshots, ReconciliationRunRepository runs,
                                 ReconciliationItemRepository items, AuditService audit) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.holds = holds;
        this.pendings = pendings;
        this.queueMessages = queueMessages;
        this.snapshots = snapshots;
        this.runs = runs;
        this.items = items;
        this.audit = audit;
    }

    @Transactional
    public ReconciliationRun run(LocalDate day, String trigger) {
        Instant from = day.atStartOfDay(LUANDA).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(LUANDA).toInstant();
        ReconciliationRun run = new ReconciliationRun();
        run.setBusinessDate(day);
        run.setTriggeredBy(trigger);
        runs.save(run);
        List<ReconciliationItem> found = new ArrayList<>();

        List<LedgerEntry> entries = ledger.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByAccountIdAscSeqAsc(from, to);
        Map<UUID, List<LedgerEntry>> byAccount = entries.stream().collect(Collectors.groupingBy(LedgerEntry::getAccountId));
        BigDecimal allDebits = BigDecimal.ZERO, allCredits = BigDecimal.ZERO, closingSum = BigDecimal.ZERO;

        List<Account> all = accounts.findAll();
        for (Account a : all) {
            List<LedgerEntry> day1 = byAccount.getOrDefault(a.getId(), List.of());
            BigDecimal opening = snapshots.findByAccountIdAndBusinessDate(a.getId(), day.minusDays(1)).map(DailySnapshot::getClosingBalance)
                    .orElseGet(() -> ledger.findFirstByAccountIdAndCreatedAtLessThanOrderBySeqDesc(a.getId(), from)
                            .map(LedgerEntry::getBalanceAfter).orElse(BigDecimal.ZERO.setScale(2)));
            BigDecimal debits = sum(day1, LedgerEntry.Direction.DEBIT), credits = sum(day1, LedgerEntry.Direction.CREDIT);
            BigDecimal expected = opening.add(credits).subtract(debits);
            BigDecimal actual = day1.isEmpty() ? opening : day1.get(day1.size() - 1).getBalanceAfter();
            if (expected.compareTo(actual) != 0) {
                found.add(item(run, "ERROR", "BALANCE_MISMATCH", a.getId(), expected, actual, "opening+credits-debits != closing in the ledger for " + a.getIban()));
            }
            DailySnapshot snap = snapshots.findByAccountIdAndBusinessDate(a.getId(), day).orElseGet(DailySnapshot::new);
            snap.setAccountId(a.getId());
            snap.setBusinessDate(day);
            snap.setOpeningBalance(opening);
            snap.setTotalDebits(debits);
            snap.setTotalCredits(credits);
            snap.setClosingBalance(actual);
            snap.setEntries(day1.size());
            snapshots.save(snap);
            allDebits = allDebits.add(debits);
            allCredits = allCredits.add(credits);
            closingSum = closingSum.add(actual);

            // held vs ACTIVE holds (current state; meaningful for the day that just closed)
            BigDecimal activeHolds = holds.activeTotal(a.getId());
            if (a.getHeld().compareTo(activeHolds) != 0) {
                found.add(item(run, "ERROR", "HOLD_MISMATCH", a.getId(), activeHolds, a.getHeld(), "held amount differs from the sum of active holds for " + a.getIban()));
            }
        }

        entries.stream().collect(Collectors.groupingBy(LedgerEntry::getTransferRef)).forEach((ref, group) -> {
            BigDecimal d = sum(group, LedgerEntry.Direction.DEBIT), c = sum(group, LedgerEntry.Direction.CREDIT);
            if (d.compareTo(c) != 0) found.add(item(run, "ERROR", "UNBALANCED_TRANSACTION", null, d, c, "transaction " + ref + " debits != credits"));
        });
        if (closingSum.compareTo(BigDecimal.ZERO) != 0) {
            found.add(item(run, "ERROR", "BOOKS_OUT_OF_BALANCE", null, BigDecimal.ZERO, closingSum, "sum of closing balances is not zero"));
        }
        for (PendingTransfer p : pendings.findByStatusAndSettledAtGreaterThanEqualAndSettledAtLessThan(PendingTransfer.Status.SETTLED, from, to)) {
            if (!ledger.existsByTransferRef(p.getId())) found.add(item(run, "ERROR", "SETTLED_WITHOUT_LEDGER", p.getAccountId(), p.getAmount(), BigDecimal.ZERO, "transfer " + p.getId()));
        }
        for (PendingTransfer p : pendings.findByStatusAndCreatedAtBefore(PendingTransfer.Status.PENDING, Instant.now().minus(java.time.Duration.ofHours(24)))) {
            found.add(item(run, "WARNING", "STALE_PENDING_TRANSFER", p.getAccountId(), p.getAmount(), null, "transfer " + p.getId() + " pending for more than 24h"));
        }
        long dead = queueMessages.countByStatus(QueueMessage.Status.DEAD);
        if (dead > 0) found.add(item(run, "WARNING", "DEAD_LETTERS", null, null, BigDecimal.valueOf(dead), dead + " message(s) in the dead-letter queue"));

        items.saveAll(found);
        long errors = found.stream().filter(i -> "ERROR".equals(i.getSeverity())).count();
        run.setAccountsChecked(all.size());
        run.setEntriesChecked(entries.size());
        run.setTotalDebits(allDebits);
        run.setTotalCredits(allCredits);
        run.setDiscrepancies(found.size());
        run.setStatus(errors == 0 ? (found.isEmpty() ? "OK" : "OK_WITH_WARNINGS") : "DISCREPANCIES");
        run.setFinishedAt(Instant.now());
        runs.save(run);

        audit.system("RECONCILIATION", errors == 0 ? "SUCCESS" : "FAILED", "RECONCILIATION_RUN", run.getId().toString(),
                "date=" + day + " trigger=" + trigger + " status=" + run.getStatus() + " items=" + found.size());
        if (errors > 0) log.error("reconciliation {} found {} error(s): see run {}", day, errors, run.getId());
        else log.info("reconciliation {} {} accounts={} entries={}", day, run.getStatus(), all.size(), entries.size());
        return run;
    }

    private static BigDecimal sum(List<LedgerEntry> list, LedgerEntry.Direction dir) {
        return list.stream().filter(e -> e.getDirection() == dir).map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private static ReconciliationItem item(ReconciliationRun run, String severity, String type, UUID account, BigDecimal expected, BigDecimal actual, String detail) {
        ReconciliationItem i = new ReconciliationItem();
        i.setRunId(run.getId());
        i.setSeverity(severity);
        i.setType(type);
        i.setAccountId(account);
        i.setExpected(expected);
        i.setActual(actual);
        i.setDetail(detail.substring(0, Math.min(detail.length(), 300)));
        return i;
    }
}
