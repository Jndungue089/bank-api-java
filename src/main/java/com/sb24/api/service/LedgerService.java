package com.sb24.api.service;

import com.sb24.api.crypto.FieldCipher;
import com.sb24.api.domain.Account;
import com.sb24.api.domain.LedgerEntry;
import com.sb24.api.repo.LedgerRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Append-only ledger. Every row carries an HMAC over its own content AND the previous row's hash for that account
 * (a hash chain keyed with a server secret). Editing, deleting or re-ordering history, even directly in the
 * database, breaks the chain and is caught by the nightly integrity job. Callers must hold the account row lock.
 */
@Service
public class LedgerService {
    private final LedgerRepository ledger;
    private final FieldCipher cipher;

    public LedgerService(LedgerRepository ledger, FieldCipher cipher) {
        this.ledger = ledger;
        this.cipher = cipher;
    }

    public LedgerEntry append(Account a, UUID ref, LedgerEntry.Kind kind, LedgerEntry.Direction dir, BigDecimal amount,
                              String counterparty, String description) {
        LedgerEntry e = new LedgerEntry();
        e.setAccountId(a.getId());
        e.setSeq(a.getLedgerSeq() + 1);
        e.setTransferRef(ref);
        e.setKind(kind);
        e.setDirection(dir);
        e.setAmount(amount.setScale(2, RoundingMode.UNNECESSARY));
        e.setBalanceAfter(a.getBalance().setScale(2, RoundingMode.UNNECESSARY));
        e.setCounterpartyIban(counterparty);
        e.setDescription(description);
        e.setPrevHash(a.getLastHash());
        e.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        e.setHash(hashOf(e));
        a.setLedgerSeq(e.getSeq());
        a.setLastHash(e.getHash());
        return ledger.save(e);
    }

    public String hashOf(LedgerEntry e) {
        String canonical = String.join("|", e.getAccountId().toString(), Long.toString(e.getSeq()), e.getTransferRef().toString(),
                e.getKind().name(), e.getDirection().name(), e.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                e.getBalanceAfter().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), nz(e.getCounterpartyIban()),
                nz(e.getDescription()), Long.toString(e.getCreatedAt().toEpochMilli()), e.getPrevHash() == null ? "GENESIS" : e.getPrevHash());
        return cipher.ledgerMac(canonical);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
