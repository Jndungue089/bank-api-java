package com.sb24.api.repo;

import com.sb24.api.domain.PaymentProvider;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentProviderRepository extends JpaRepository<PaymentProvider, String> {
    List<PaymentProvider> findByEnabledTrueOrderByCodeAsc();
}
