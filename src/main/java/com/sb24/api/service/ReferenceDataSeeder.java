package com.sb24.api.service;

import com.sb24.api.domain.PaymentProvider;
import com.sb24.api.domain.SavingsProduct;
import com.sb24.api.repo.PaymentProviderRepository;
import com.sb24.api.repo.SavingsProductRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Seeder 2/4 (all profiles): reference data the API cannot work without. Insert-if-missing, never overwrites edits. */
@Component
@Order(2)
public class ReferenceDataSeeder implements ApplicationRunner {
    private final PaymentProviderRepository providers;
    private final SavingsProductRepository savings;

    public ReferenceDataSeeder(PaymentProviderRepository providers, SavingsProductRepository savings) {
        this.providers = providers;
        this.savings = savings;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        add("UNITEL", "Unitel (carregamento)", "^9\\d{8}$", "Número de telemóvel (9 dígitos)");
        add("AFRICELL", "Africell (carregamento)", "^9\\d{8}$", "Número de telemóvel (9 dígitos)");
        add("ENDE", "ENDE (electricidade)", "^\\d{10}$", "Número do contador (10 dígitos)");
        add("EPAL", "EPAL (água)", "^\\d{8,12}$", "Número do cliente (8 a 12 dígitos)");
        add("DSTV", "DStv", "^\\d{10}$", "Número do smartcard (10 dígitos)");
        product("FLEX", "Poupança Flexível", "0.0400", "1000.00", 0);
        product("TERM90", "Depósito a Prazo 90 dias", "0.0800", "50000.00", 90);
        product("TERM180", "Depósito a Prazo 180 dias", "0.1000", "100000.00", 180);
    }

    private void product(String code, String name, String rate, String min, int termDays) {
        if (savings.existsById(code)) return;
        SavingsProduct p = new SavingsProduct();
        p.setCode(code);
        p.setName(name);
        p.setAnnualRate(new java.math.BigDecimal(rate));
        p.setMinAmount(new java.math.BigDecimal(min));
        p.setTermDays(termDays);
        savings.save(p);
    }

    private void add(String code, String name, String pattern, String hint) {
        if (providers.existsById(code)) return;
        PaymentProvider p = new PaymentProvider();
        p.setCode(code);
        p.setName(name);
        p.setReferencePattern(pattern);
        p.setReferenceHint(hint);
        providers.save(p);
    }
}
