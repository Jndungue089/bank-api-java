package com.sb24.api.web;

import com.sb24.api.access.Perm;
import com.sb24.api.access.RequiresPermission;
import com.sb24.api.domain.DirectDebitMandate.Status;
import com.sb24.api.service.DirectDebitService;
import com.sb24.api.web.Dtos.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Customer side of direct debit: manage the mandates that creditors may collect against. */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Débito directo")
@RestController
@RequestMapping("/api/v1/direct-debits")
public class DirectDebitController {
    private final DirectDebitService service;

    public DirectDebitController(DirectDebitService service) { this.service = service; }

    @Operation(summary = "Autorizar um débito directo (mandato: credor, referência, máximo por cobrança, frequência)")
    @RequiresPermission(Perm.DIRECT_DEBIT_MANAGE)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MandateView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MandateRequest r) { return service.create(AccountController.uid(jwt), r); }

    @Operation(summary = "Os meus mandatos")
    @RequiresPermission(Perm.DIRECT_DEBIT_READ)
    @GetMapping
    public List<MandateView> list(@AuthenticationPrincipal Jwt jwt) { return service.list(AccountController.uid(jwt)); }

    @Operation(summary = "Cobranças de um mandato")
    @RequiresPermission(Perm.DIRECT_DEBIT_READ)
    @GetMapping("/{id}/collections")
    public List<CollectionView> collections(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return service.collectionsOf(AccountController.uid(jwt), id); }

    @Operation(summary = "Pausar mandato")
    @RequiresPermission(Perm.DIRECT_DEBIT_MANAGE)
    @PostMapping("/{id}/pause")
    public MandateView pause(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return service.change(AccountController.uid(jwt), id, Status.PAUSED); }

    @Operation(summary = "Retomar mandato")
    @RequiresPermission(Perm.DIRECT_DEBIT_MANAGE)
    @PostMapping("/{id}/resume")
    public MandateView resume(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return service.change(AccountController.uid(jwt), id, Status.ACTIVE); }

    @Operation(summary = "Cancelar mandato (definitivo)")
    @RequiresPermission(Perm.DIRECT_DEBIT_MANAGE)
    @PostMapping("/{id}/cancel")
    public MandateView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return service.change(AccountController.uid(jwt), id, Status.CANCELLED); }
}
