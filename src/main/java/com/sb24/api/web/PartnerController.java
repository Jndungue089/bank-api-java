package com.sb24.api.web;

import com.sb24.api.access.Perm;
import com.sb24.api.access.PublicEndpoint;
import com.sb24.api.access.RequiresPermission;
import com.sb24.api.service.DirectDebitService;
import com.sb24.api.service.PartnerAuthService;
import com.sb24.api.web.Dtos.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Creditor-facing API: token endpoint plus collections, scoped by ABAC to the creditor's own mandates. */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Credores (débito directo)")
@RestController
@RequestMapping("/api/v1/partner")
public class PartnerController {
    private final PartnerAuthService auth;
    private final DirectDebitService directDebit;

    public PartnerController(PartnerAuthService auth, DirectDebitService directDebit) {
        this.auth = auth;
        this.directDebit = directDebit;
    }

    @Operation(summary = "Token do credor (client id + secret)")
    @PublicEndpoint
    @PostMapping("/auth/token")
    public TokenResponse token(@Valid @RequestBody PartnerTokenRequest r) { return auth.token(r); }

    @Operation(summary = "Submeter cobrança de débito directo (idempotente por externalRef; processada de forma assíncrona)")
    @RequiresPermission(Perm.COLLECTION_SUBMIT)
    @PostMapping("/collections")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CollectionView submit(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CollectionRequest r) {
        return directDebit.submit(UUID.fromString(jwt.getSubject()), r);
    }

    @Operation(summary = "Estado de uma cobrança própria")
    @RequiresPermission(Perm.COLLECTION_READ)
    @GetMapping("/collections/{id}")
    public CollectionView collection(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return directDebit.collection(UUID.fromString(jwt.getSubject()), id);
    }
}
