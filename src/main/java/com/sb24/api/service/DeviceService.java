package com.sb24.api.service;

import com.sb24.api.audit.RequestContext;
import com.sb24.api.domain.KnownDevice;
import com.sb24.api.queue.NotificationService;
import com.sb24.api.repo.KnownDeviceRepository;
import com.sb24.api.util.Hashing;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Remembers the devices a customer logs in from; a login from an unseen device is audited and notified. */
@Service
public class DeviceService {
    private final KnownDeviceRepository devices;
    private final AuditService audit;
    private final NotificationService notifications;

    public DeviceService(KnownDeviceRepository devices, AuditService audit, NotificationService notifications) {
        this.devices = devices;
        this.audit = audit;
        this.notifications = notifications;
    }

    @Transactional
    public void recordLogin(UUID userId) {
        RequestContext ctx = RequestContext.current();
        if (ctx == null) return;
        String basis = ctx.deviceId() != null ? "id:" + ctx.deviceId() : "ua:" + String.valueOf(ctx.userAgent());
        String key = Hashing.sha256Hex(basis);
        var known = devices.findByUserIdAndDeviceKey(userId, key);
        if (known.isPresent()) {
            known.get().setLastSeen(Instant.now());
            return;
        }
        boolean first = devices.countByUserId(userId) == 0;
        KnownDevice d = new KnownDevice();
        d.setUserId(userId);
        d.setDeviceKey(key);
        d.setUserAgent(ctx.userAgent());
        d.setFirstIp(ctx.ip());
        devices.save(d);
        if (!first) {
            audit.customer(userId, "NEW_DEVICE_LOGIN", "SUCCESS", "DEVICE", key.substring(0, 12), "ip=" + ctx.ip());
            notifications.notify(userId, "Novo dispositivo iniciou sessão na sua conta. Se não foi você, contacte o banco.");
        }
    }
}
