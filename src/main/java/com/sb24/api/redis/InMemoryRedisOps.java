package com.sb24.api.redis;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default when app.redis.enabled=false. Correct for ONE instance only (the state is local to the JVM). */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryRedisOps implements RedisOps {
    private record Entry(String value, long expiresAt) {}
    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();

    @Override
    public boolean setIfAbsent(String key, String value, long ttlMillis) {
        long now = System.currentTimeMillis();
        boolean[] won = {false};
        map.compute(key, (k, old) -> {
            if (old == null || old.expiresAt() <= now) { won[0] = true; return new Entry(value, now + ttlMillis); }
            return old;
        });
        return won[0];
    }

    @Override public void set(String key, String value, long ttlMillis) { map.put(key, new Entry(value, System.currentTimeMillis() + ttlMillis)); }

    @Override
    public boolean exists(String key) {
        Entry e = map.get(key);
        if (e == null) return false;
        if (e.expiresAt() <= System.currentTimeMillis()) { map.remove(key, e); return false; }
        return true;
    }

    @Override public void deleteIfEquals(String key, String value) { map.computeIfPresent(key, (k, e) -> e.value().equals(value) ? null : e); }

    @Override public boolean supportsDistributedBuckets() { return false; }

    @Override public long[] tokenBucket(String key, int capacity, long periodMillis) { throw new UnsupportedOperationException(); }
}
