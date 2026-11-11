package com.sb24.api.web;

import com.sb24.api.config.AppProperties;
import com.sb24.api.service.IdempotencyService;
import com.sb24.api.service.PaymentService;
import com.sb24.api.service.TransferService;
import com.sb24.api.util.ClientIp;
import com.sb24.api.web.Dtos.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Transferências e pagamentos")
@RestController
@RequestMapping("/api/v1")
public class MoneyController {
    private final TransferService transfers;
    private final PaymentService payments;
    private final AppProperties props;

    public MoneyController(TransferService transfers, PaymentService payments, AppProperties props) {
        this.transfers = transfers;
        this.payments = payments;
        this.props = props;
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Transferência (requer Idempotency-Key)")
    @PostMapping("/transfers")
    @com.sb24.api.access.RequiresPermission(com.sb24.api.access.Perm.TRANSFER_CREATE)
    public ResponseEntity<Receipt> transfer(@AuthenticationPrincipal Jwt jwt,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                            @Valid @RequestBody TransferRequest r, HttpServletRequest req) {
        return respond(transfers.transfer(AccountController.uid(jwt), r, key, ClientIp.resolve(req, props.security().trustForwardHeaders())));
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Estado de uma transferência interbancária (PENDING, SETTLED, REJECTED)")
    @GetMapping("/transfers/{reference}")
    @com.sb24.api.access.RequiresPermission(com.sb24.api.access.Perm.TRANSFER_READ)
    public TransferStatusView transferStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable java.util.UUID reference) {
        return transfers.status(AccountController.uid(jwt), reference);
    }

    @io.swagger.v3.oas.annotations.Operation(summary = "Entidades de pagamento")
    @GetMapping("/payments/providers")
    @com.sb24.api.access.RequiresPermission(com.sb24.api.access.Perm.PAYMENT_READ)
    public List<ProviderView> providers() { return payments.providers(); }

    @io.swagger.v3.oas.annotations.Operation(summary = "Pagar serviço (requer Idempotency-Key)")
    @PostMapping("/payments")
    @com.sb24.api.access.RequiresPermission(com.sb24.api.access.Perm.PAYMENT_CREATE)
    public ResponseEntity<Receipt> pay(@AuthenticationPrincipal Jwt jwt,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                       @Valid @RequestBody PaymentRequest r, HttpServletRequest req) {
        return respond(payments.pay(AccountController.uid(jwt), r, key, ClientIp.resolve(req, props.security().trustForwardHeaders())));
    }

    private static ResponseEntity<Receipt> respond(IdempotencyService.Result<Receipt> result) {
        var builder = result.replayed() ? ResponseEntity.ok().header("Idempotent-Replayed", "true") : ResponseEntity.status(201);
        return builder.body(result.body());
    }
}
