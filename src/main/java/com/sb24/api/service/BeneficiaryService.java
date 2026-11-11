package com.sb24.api.service;

import com.sb24.api.access.Perm;
import com.sb24.api.access.PolicyService;
import com.sb24.api.access.Resource;
import com.sb24.api.access.Subject;
import com.sb24.api.config.AppProperties;
import com.sb24.api.domain.Account;
import com.sb24.api.domain.Beneficiary;
import com.sb24.api.queue.NotificationService;
import com.sb24.api.repo.AccountRepository;
import com.sb24.api.repo.BeneficiaryRepository;
import com.sb24.api.repo.UserRepository;
import com.sb24.api.util.IbanUtil;
import com.sb24.api.web.Dtos.BeneficiaryRequest;
import com.sb24.api.web.Dtos.BeneficiaryView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Saved payees ("beneficiários SB24"). Same-bank IBANs are resolved so the customer can confirm who they are paying. */
@Service
public class BeneficiaryService {
    private final BeneficiaryRepository repo;
    private final AccountRepository accounts;
    private final UserRepository users;
    private final PolicyService policy;
    private final AuditService audit;
    private final NotificationService notifications;
    private final AppProperties props;

    public BeneficiaryService(BeneficiaryRepository repo, AccountRepository accounts, UserRepository users, PolicyService policy,
                              AuditService audit, NotificationService notifications, AppProperties props) {
        this.repo = repo;
        this.accounts = accounts;
        this.users = users;
        this.policy = policy;
        this.audit = audit;
        this.notifications = notifications;
        this.props = props;
    }

    @Transactional
    public BeneficiaryView add(UUID userId, BeneficiaryRequest r) {
        policy.require(Subject.customer(userId), Perm.BENEFICIARY_MANAGE, Resource.none());
        if (!IbanUtil.isValid(r.iban())) throw ApiException.unprocessable("INVALID_IBAN", "IBAN inválido.");
        if (repo.countByUserId(userId) >= props.banking().maxBeneficiaries()) {
            throw ApiException.unprocessable("BENEFICIARY_LIMIT", "Atingiu o máximo de beneficiários.");
        }
        if (repo.existsByUserIdAndIban(userId, r.iban())) throw ApiException.conflict("BENEFICIARY_EXISTS", "Esse IBAN já está nos seus beneficiários.");
        Beneficiary b = new Beneficiary();
        b.setUserId(userId);
        b.setAlias(r.alias().trim());
        b.setName(r.name().trim());
        b.setIban(r.iban());
        b.setBankCode(IbanUtil.bankCode(r.iban()));
        repo.save(b);
        audit.customer(userId, "BENEFICIARY_ADD", "SUCCESS", "BENEFICIARY", b.getId().toString(), "bank=" + b.getBankCode());
        notifications.notify(userId, "Beneficiário \"" + b.getAlias() + "\" adicionado. Por segurança, transferências altas para ele ficam limitadas nas primeiras 24 horas.");
        return view(b);
    }

    @Transactional(readOnly = true)
    public List<BeneficiaryView> list(UUID userId) {
        policy.require(Subject.customer(userId), Perm.BENEFICIARY_READ, Resource.none());
        return repo.findByUserIdOrderByAliasAsc(userId).stream().map(this::view).toList();
    }

    @Transactional
    public void remove(UUID userId, UUID id) {
        Beneficiary b = repo.findById(id).orElseThrow(() -> ApiException.notFound("Beneficiário não encontrado."));
        policy.require(Subject.customer(userId), Perm.BENEFICIARY_MANAGE, Resource.of("BENEFICIARY", id, b.getUserId(), Map.of()));
        repo.delete(b);
        audit.customer(userId, "BENEFICIARY_REMOVE", "SUCCESS", "BENEFICIARY", id.toString(), null);
    }

    /** Loads a beneficiary for a transfer; the transfer's own policy check then applies the cool-off rule to it. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Beneficiary loadForTransfer(UUID userId, UUID id) {
        Beneficiary b = repo.findById(id).orElseThrow(() -> ApiException.notFound("Beneficiário não encontrado."));
        policy.require(Subject.customer(userId), Perm.BENEFICIARY_READ, Resource.of("BENEFICIARY", id, b.getUserId(), Map.of()));
        return b;
    }

    public void touch(Beneficiary b) { b.setLastUsedAt(Instant.now()); }

    private BeneficiaryView view(Beneficiary b) {
        String holder = null;
        if (b.getBankCode().equals(props.banking().bankCode())) {
            holder = accounts.findByIban(b.getIban()).map(Account::getOwnerId).flatMap(users::findById).map(u -> mask(u.getFullName())).orElse(null);
        }
        return new BeneficiaryView(b.getId(), b.getAlias(), b.getName(), b.getIban(), b.getBankCode(), holder, b.getCreatedAt(), b.getLastUsedAt());
    }

    private static String mask(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.split(" ")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(part.isEmpty() ? "" : part.charAt(0)).append("*".repeat(Math.max(0, Math.min(part.length() - 1, 6))));
        }
        return sb.toString();
    }
}
