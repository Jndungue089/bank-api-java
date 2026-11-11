package com.sb24.api.redis;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Distributed mutex so a cron job runs once per tick even with several API instances (SET NX PX + token-checked release). */
@Component
public class JobLock {
    private static final Logger log = LoggerFactory.getLogger(JobLock.class);
    private final RedisOps ops;

    public JobLock(RedisOps ops) { this.ops = ops; }

    public boolean runExclusive(String name, Duration ttl, Runnable job) {
        String token = UUID.randomUUID().toString();
        String key = "lock:" + name;
        boolean acquired;
        try { acquired = ops.setIfAbsent(key, token, ttl.toMillis()); }
        catch (RuntimeException e) { log.error("lock {} unavailable, skipping run", name, e); return false; }
        if (!acquired) { log.debug("job {} already running elsewhere", name); return false; }
        try { job.run(); return true; }
        finally { try { ops.deleteIfEquals(key, token); } catch (RuntimeException e) { log.warn("lock release failed", e); } }
    }
}
