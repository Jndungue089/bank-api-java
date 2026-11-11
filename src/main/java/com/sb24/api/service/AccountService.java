package com.sb24.api.service;

import com.sb24.api.config.AppProperties;
import com.sb24.api.domain.*;
import com.sb24.api.repo.AccountRepository;
import com.sb24.api.repo.LedgerRepository;
import com.sb24.api.repo.UserRepository;
import com.sb24.api.util.IbanUtil;
import com.sb24.api.web.Dtos.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final UserRepository users;
    private final AppProperties props;
    private final LedgerService ledgerService;
    private final com.sb24.api.access.PolicyService policy;

    public AccountService(AccountRepository accounts, LedgerRepository ledger, UserRepository users, LedgerService ledgerService, AppProperties props,
                          com.sb24.api.access.PolicyService policy) {
        this.policy = policy;
        this.ledgerService = ledgerService;
        this.accounts = accounts;
        this.ledger = ledger;
        this.users = users;
        this.props = props;
    }

    @Transactional
    public Account openCurrentAccount(UUID ownerId) {
        return create(ownerId, AccountType.CURRENT, null);
    }

    @Transactional
    public Account create(UUID ownerId, AccountType type, String purpose) {
        String iban;
        do {
            iban = IbanUtil.generate(props.banking().bankCode(), props.banking().branchCode(), IbanUtil.randomAccountNumber());
        } while (accounts.existsByIban(iban));
        Account a = new Account();
        a.setOwnerId(ownerId);
        a.setIban(iban);
        a.setType(type);
        a.setPurpose(purpose);
        return accounts.save(a);
    }

    @Transactional(readOnly = true)
    public ProfileView profile(UUID userId) {
        AppUser u = users.findById(userId).orElseThrow(() -> ApiException.notFound("Utilizador não encontrado."));
        return new ProfileView(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), list(userId));
    }

    @Transactional(readOnly = true)
    public List<AccountView> list(UUID userId) {
        return accounts.findByOwnerIdOrderByCreatedAtAsc(userId).stream().map(AccountService::view).toList();
    }

    /** Ownership is enforced in the query itself (BOLA/IDOR defence): somebody else's account looks like "not found". */
    @Transactional(readOnly = true)
    public AccountView get(UUID userId, UUID accountId) {
        Account a = accounts.findById(accountId).orElseThrow(() -> ApiException.notFound("Conta não encontrada."));
        policy.require(com.sb24.api.access.Subject.customer(userId), com.sb24.api.access.Perm.ACCOUNT_READ, com.sb24.api.access.Resource.account(a, java.util.Map.of()));
        return view(a);
    }

    @Transactional(readOnly = true)
    public PageView<LedgerView> statement(UUID userId, UUID accountId, int page, int size) {
        Account a = accounts.findById(accountId).orElseThrow(() -> ApiException.notFound("Conta não encontrada."));
        policy.require(com.sb24.api.access.Subject.customer(userId), com.sb24.api.access.Perm.STATEMENT_READ, com.sb24.api.access.Resource.account(a, java.util.Map.of()));
        Page<LedgerEntry> p = ledger.findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        return new PageView<>(p.map(AccountService::view).getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }

    /** Simulated cash-in (dev seeding and tests only; no HTTP endpoint exposes this). Debits CLEARING so the books stay balanced. */
    @Transactional
    public void cashIn(UUID accountId, BigDecimal amount, String description) {
        UUID clearingId = accounts.findIdByPurpose("CLEARING").orElseThrow();
        List<Account> locked = accounts.lockAllById(java.util.stream.Stream.of(accountId, clearingId).sorted().toList());
        Account target = locked.stream().filter(a -> a.getId().equals(accountId)).findFirst().orElseThrow();
        Account clearing = locked.stream().filter(a -> a.getId().equals(clearingId)).findFirst().orElseThrow();
        UUID ref = UUID.randomUUID();
        clearing.setBalance(clearing.getBalance().subtract(amount));
        target.setBalance(target.getBalance().add(amount));
        ledgerService.append(clearing, ref, LedgerEntry.Kind.OPENING, LedgerEntry.Direction.DEBIT, amount, target.getIban(), description);
        ledgerService.append(target, ref, LedgerEntry.Kind.OPENING, LedgerEntry.Direction.CREDIT, amount, clearing.getIban(), description);
    }

    static AccountView view(Account a) {
        return new AccountView(a.getId(), a.getIban(), a.getType().name(), a.getCurrency(), a.getBalance(), a.getHeld(), a.available(), a.getStatus());
    }

    static LedgerView view(LedgerEntry e) {
        return new LedgerView(e.getId(), e.getTransferRef(), e.getKind().name(), e.getDirection().name(), e.getAmount(),
                e.getBalanceAfter(), e.getCounterpartyIban(), e.getDescription(), e.getCreatedAt());
    }
}
