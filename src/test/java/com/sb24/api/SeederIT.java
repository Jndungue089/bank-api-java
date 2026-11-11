package com.sb24.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import com.sb24.api.service.ReferenceDataSeeder;
import com.sb24.api.service.SystemBootstrap;

class SeederIT extends BaseIT {
    @Autowired ReferenceDataSeeder referenceData;
    @Autowired SystemBootstrap systemBootstrap;

    @Test
    void referenceDataAndSystemAccountsExistAndSeedingIsIdempotent() throws Exception {
        referenceData.run(new DefaultApplicationArguments());
        systemBootstrap.run(new DefaultApplicationArguments());
        assertEquals(5, db.queryForObject("select count(*) from payment_provider", Integer.class));
        for (String purpose : new String[] {"CLEARING", "PAYMENTS", "FEES"}) {
            assertEquals(1, db.queryForObject("select count(*) from account where purpose = ?", Integer.class, purpose));
        }
        assertEquals(1, db.queryForObject("select count(*) from app_user where role = 'SYSTEM'", Integer.class));
        Client c = registerAndLogin();
        mvc.perform(get("/api/v1/payments/providers").with(from(c.ip())).header("Authorization", "Bearer " + c.access()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void adminsAndCustomersLiveInDifferentTables() throws Exception {
        Admin admin = newAdmin(com.sb24.api.domain.AdminRole.AUDITOR);
        assertEquals(0, db.queryForObject("select count(*) from app_user where email = ?", Integer.class, admin.email()));
        assertEquals(1, db.queryForObject("select count(*) from admin_user where email = ?", Integer.class, admin.email()));
    }
}
