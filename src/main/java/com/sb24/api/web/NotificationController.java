package com.sb24.api.web;

import com.sb24.api.repo.NotificationRepository;
import com.sb24.api.web.Dtos.NotificationView;
import com.sb24.api.web.Dtos.PageView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Notificações")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationRepository notifications;

    public NotificationController(NotificationRepository notifications) { this.notifications = notifications; }

    @io.swagger.v3.oas.annotations.Operation(summary = "As minhas notificações (geradas de forma assíncrona pela fila)")
    @GetMapping
    @com.sb24.api.access.RequiresPermission(com.sb24.api.access.Perm.NOTIFICATION_READ)
    public PageView<NotificationView> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<NotificationView> p = notifications.findByUserIdOrderByCreatedAtDesc(AccountController.uid(jwt), PageRequest.of(page, size))
                .map(n -> new NotificationView(n.getId(), n.getChannel(), n.getMessage(), n.getCreatedAt()));
        return new PageView<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
