package com.sb24.api.web;

import com.sb24.api.access.Perm;
import com.sb24.api.access.RequiresPermission;
import com.sb24.api.service.IdempotencyService;
import com.sb24.api.service.SavingsService;
import com.sb24.api.web.Dtos.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Poupança")
@RestController
@RequestMapping("/api/v1/savings")
public class SavingsController {
    private final SavingsService service;

    public SavingsController(SavingsService service) { this.service = service; }

    @Operation(summary = "Produtos de poupança e depósitos a prazo")
    @RequiresPermission(Perm.SAVINGS_READ)
    @GetMapping("/products")
    public List<SavingsProductView> products(@AuthenticationPrincipal Jwt jwt) { return service.products(AccountController.uid(jwt)); }

    @Operation(summary = "As minhas poupanças")
    @RequiresPermission(Perm.SAVINGS_READ)
    @GetMapping
    public List<SavingsView> list(@AuthenticationPrincipal Jwt jwt) { return service.list(AccountController.uid(jwt)); }

    @Operation(summary = "Subscrever poupança (requer Idempotency-Key)")
    @RequiresPermission(Perm.SAVINGS_MANAGE)
    @PostMapping
    public ResponseEntity<SavingsReceipt> subscribe(@AuthenticationPrincipal Jwt jwt, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                    @Valid @RequestBody SubscribeSavingsRequest r) {
        return respond(service.subscribe(AccountController.uid(jwt), r, key));
    }

    @Operation(summary = "Depositar na poupança (requer Idempotency-Key)")
    @RequiresPermission(Perm.SAVINGS_MANAGE)
    @PostMapping("/{id}/deposit")
    public ResponseEntity<SavingsReceipt> deposit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                  @Valid @RequestBody SavingsMoveRequest r) {
        return respond(service.deposit(AccountController.uid(jwt), id, r, key));
    }

    @Operation(summary = "Levantar da poupança (depósitos a prazo só após o vencimento; requer Idempotency-Key)")
    @RequiresPermission(Perm.SAVINGS_MANAGE)
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<SavingsReceipt> withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                   @Valid @RequestBody SavingsMoveRequest r) {
        return respond(service.withdraw(AccountController.uid(jwt), id, r, key));
    }

    private static ResponseEntity<SavingsReceipt> respond(IdempotencyService.Result<SavingsReceipt> r) {
        var b = r.replayed() ? ResponseEntity.ok().header("Idempotent-Replayed", "true") : ResponseEntity.status(201);
        return b.body(r.body());
    }
}
