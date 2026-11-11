package com.sb24.api.repo;

import com.sb24.api.domain.SavingsProduct;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavingsProductRepository extends JpaRepository<SavingsProduct, String> {
    List<SavingsProduct> findByEnabledTrueOrderByCodeAsc();
}
