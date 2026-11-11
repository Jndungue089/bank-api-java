package com.sb24.api.web;

import com.sb24.api.service.AdminAuthService;
import com.sb24.api.web.Dtos.AdminLoginRequest;
import com.sb24.api.web.Dtos.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Administração")
@com.sb24.api.access.PublicEndpoint
@RestController
@RequestMapping("/api/v1/admin/auth")
public class AdminAuthController {
    private final AdminAuthService auth;

    public AdminAuthController(AdminAuthService auth) { this.auth = auth; }

    @io.swagger.v3.oas.annotations.Operation(summary = "Login de administrador (tabela e realm separados dos clientes)")
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody AdminLoginRequest r) { return auth.login(r); }
}
