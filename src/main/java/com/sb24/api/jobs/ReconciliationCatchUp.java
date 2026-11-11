package com.sb24.api.jobs;

import com.sb24.api.repo.ReconciliationRunRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** If the app was down at 00:00 Luanda, queue yesterday's reconciliation at start-up (dedup key makes this safe). */
@Component
@Order(10)
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationCatchUp implements ApplicationRunner {
    private final MaintenanceJobs jobs;
    private final ReconciliationRunRepository runs;

    public ReconciliationCatchUp(MaintenanceJobs jobs, ReconciliationRunRepository runs) {
        this.jobs = jobs;
        this.runs = runs;
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalDate yesterday = LocalDate.now(ZoneId.of("Africa/Luanda")).minusDays(1);
        if (!runs.existsByBusinessDate(yesterday)) jobs.enqueueReconciliation(yesterday, "CRON");
    }
}
