package com.sb24.api.web;

import com.sb24.api.access.Perm;
import com.sb24.api.access.RequiresPermission;
import com.sb24.api.service.BeneficiaryService;
import com.sb24.api.web.Dtos.BeneficiaryRequest;
import com.sb24.api.web.Dtos.BeneficiaryView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Beneficiários")
@RestController
@RequestMapping("/api/v1/beneficiaries")
public class BeneficiaryController {
    private final BeneficiaryService service;

    public BeneficiaryController(BeneficiaryService service) { this.service = service; }

    @Operation(summary = "Adicionar beneficiário (IBAN validado; nas primeiras 24 h transferências altas ficam limitadas)")
    @RequiresPermission(Perm.BENEFICIARY_MANAGE)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BeneficiaryView add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BeneficiaryRequest r) { return service.add(AccountController.uid(jwt), r); }

    @Operation(summary = "Os meus beneficiários")
    @RequiresPermission(Perm.BENEFICIARY_READ)
    @GetMapping
    public List<BeneficiaryView> list(@AuthenticationPrincipal Jwt jwt) { return service.list(AccountController.uid(jwt)); }

    @Operation(summary = "Remover beneficiário")
    @RequiresPermission(Perm.BENEFICIARY_MANAGE)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.remove(AccountController.uid(jwt), id);
        return ResponseEntity.noContent().build();
    }
}
