package com.sb24.api.service;

import com.sb24.api.domain.*;
import com.sb24.api.domain.LedgerEntry.Direction;
import com.sb24.api.domain.LedgerEntry.Kind;
import com.sb24.api.repo.AccountRepository;
import com.sb24.api.repo.SavingsInterestRepository;
import com.sb24.api.repo.SavingsPlanRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Daily interest: balance x annual rate / 365, banker's rounding, paid from the bank's INTEREST account. Idempotent per account and day. */
@Service
public class InterestService {
    private final SavingsPlanRepository plans;
    private final SavingsInterestRepository interest;
    private final AccountRepository accounts;
    private final LedgerService ledgerService;
    private final AuditService audit;

    public InterestService(SavingsPlanRepository plans, SavingsInterestRepository interest, AccountRepository accounts, LedgerService ledgerService, AuditService audit) {
        this.plans = plans;
        this.interest = interest;
        this.accounts = accounts;
        this.ledgerService = ledgerService;
        this.audit = audit;
    }

    @Transactional
    public int accrue(LocalDate day) {
        UUID bankId = accounts.findIdByPurpose("INTEREST").orElseThrow();
        int credited = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (SavingsPlan plan : plans.findByStatus("ACTIVE")) {
            if (interest.existsByAccountIdAndAccrualDate(plan.getAccountId(), day)) continue;
            List<Account> locked = accounts.lockAllById(Stream.of(plan.getAccountId(), bankId).sorted().toList());
            Account savings = locked.stream().filter(a -> a.getId().equals(plan.getAccountId())).findFirst().orElseThrow();
            Account bank = locked.stream().filter(a -> a.getId().equals(bankId)).findFirst().orElseThrow();
            BigDecimal amount = savings.getBalance().multiply(plan.getAnnualRate()).divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_EVEN);
            SavingsInterest rec = new SavingsInterest();
            rec.setAccountId(savings.getId());
            rec.setAccrualDate(day);
            rec.setAmount(amount);
            interest.save(rec);
            if (amount.signum() > 0) {
                UUID ref = UUID.randomUUID();
                bank.setBalance(bank.getBalance().subtract(amount));
                savings.setBalance(savings.getBalance().add(amount));
                ledgerService.append(bank, ref, Kind.INTEREST, Direction.DEBIT, amount, savings.getIban(), "Juros " + day);
                ledgerService.append(savings, ref, Kind.INTEREST, Direction.CREDIT, amount, bank.getIban(), "Juros " + day);
                credited++;
                total = total.add(amount);
            }
        }
        audit.system("SAVINGS_INTEREST", "SUCCESS", "SAVINGS", day.toString(), "accounts=" + credited + " total=" + total);
        return credited;
    }
}
