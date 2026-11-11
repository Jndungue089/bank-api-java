package com.sb24.api.web;

import com.sb24.api.service.AccountService;
import com.sb24.api.web.Dtos.*;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Contas")
@RestController
@RequestMapping("/api/v1")
public class AccountController {
    private final AccountService accounts;

    public AccountController(AccountService accounts) { this.accounts = accounts; }

    @io.swagger.v3.oas.annotations.Operation(summary = "Perfil e contas")
    @GetMapping("/me")
    @com.sb24.api.access.RequiresPermission(com.sb24.api.access.Perm.PROFILE_READ)
    public ProfileView me(@AuthenticationPrincipal Jwt jwt) { return accounts.profile(uid(jwt)); }

    @io.swagger.v3.oas.annotations.Operation(summary = "Listar contas")
    @GetMapping("/accounts")
    @com.sb24.api.access.RequiresPermission(com.sb24.api.access.Perm.ACCOUNT_READ)
    public List<AccountView> list(@AuthenticationPrincipal Jwt jwt) { return accounts.list(uid(jwt)); }

    @io.swagger.v3.oas.annotations.Operation(summary = "Saldo de uma conta")
    @GetMapping("/accounts/{id}")
    @com.sb24.api.access.RequiresPermission(com.sb24.api.access.Perm.ACCOUNT_READ)
    public AccountView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return accounts.get(uid(jwt), id); }

    @io.swagger.v3.oas.annotations.Operation(summary = "Extracto paginado")
    @GetMapping("/accounts/{id}/transactions")
    @com.sb24.api.access.RequiresPermission(com.sb24.api.access.Perm.STATEMENT_READ)
    public PageView<LedgerView> transactions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return accounts.statement(uid(jwt), id, page, size);
    }

    static UUID uid(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
