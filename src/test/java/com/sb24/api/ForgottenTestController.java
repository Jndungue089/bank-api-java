package com.sb24.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only handler that a developer "forgot" to protect (no @RequiresPermission, no @PublicEndpoint). It sits under a
 * customer prefix so it passes the realm gate and reaches the interceptor, which must refuse it (fail closed).
 * Top-level on purpose: Spring Boot's test type filter ignores controllers nested inside test classes.
 */
@RestController
class ForgottenTestController {
    @GetMapping("/api/v1/accounts/test-forgotten")
    public String forgotten() { return "secret"; }
}
