package com.sb24.api.service;

import com.sb24.api.domain.PendingTransfer;
import com.sb24.api.util.IbanUtil;
import org.springframework.stereotype.Component;

/** Deterministic simulation: bank 9999 does not exist (rejected); bank 0050 times out on the first two attempts. */
@Component
public class SimulatedInterbankGateway implements InterbankGateway {
    @Override
    public Decision submit(PendingTransfer t, int attempt) {
        String bank = IbanUtil.bankCode(t.getToIban());
        if ("9999".equals(bank)) return new Decision(false, "BANCO_DESTINO_DESCONHECIDO");
        if ("0050".equals(bank) && attempt < 3) throw new TransientGatewayException("timeout a contactar o banco de destino");
        return new Decision(true, null);
    }
}
