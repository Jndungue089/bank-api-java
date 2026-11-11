package com.sb24.api.redis;

import com.sb24.api.config.AppProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real Redis through Lettuce. Rate limiting runs as one Lua script (atomic across all API instances); Redis TIME is
 * used so instances with skewed clocks still agree. Short command timeouts keep a slow Redis from stalling requests.
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class LettuceRedisOps implements RedisOps, DisposableBean {
    private static final String TOKEN_BUCKET = """
            local cap = tonumber(ARGV[1]); local period = tonumber(ARGV[2])
            local t = redis.call('TIME'); local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local d = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(d[1]); local ts = tonumber(d[2])
            if tokens == nil then tokens = cap; ts = now end
            local rate = cap / period
            tokens = math.min(cap, tokens + (now - ts) * rate)
            local allowed = 0; local retry = 0
            if tokens >= 1 then tokens = tokens - 1; allowed = 1 else retry = math.ceil((1 - tokens) / rate) end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', now)
            redis.call('PEXPIRE', KEYS[1], period * 2)
            return {allowed, math.floor(tokens), retry}
            """;
    private static final String DEL_IF_EQUALS =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;

    public LettuceRedisOps(AppProperties props) {
        this.client = RedisClient.create(props.redis().uri());
        this.client.setDefaultTimeout(Duration.ofMillis(500));
        this.connection = client.connect();
    }

    @Override public boolean setIfAbsent(String key, String value, long ttlMillis) {
        return connection.sync().set(key, value, SetArgs.Builder.nx().px(ttlMillis)) != null;
    }

    @Override public void set(String key, String value, long ttlMillis) { connection.sync().set(key, value, SetArgs.Builder.px(ttlMillis)); }

    @Override public boolean exists(String key) { return connection.sync().exists(key) > 0; }

    @Override public void deleteIfEquals(String key, String value) {
        connection.sync().eval(DEL_IF_EQUALS, ScriptOutputType.INTEGER, new String[] {key}, value);
    }

    @Override public boolean supportsDistributedBuckets() { return true; }

    @Override
    @SuppressWarnings("unchecked")
    public long[] tokenBucket(String key, int capacity, long periodMillis) {
        List<Object> r = connection.sync().eval(TOKEN_BUCKET, ScriptOutputType.MULTI, new String[] {key},
                String.valueOf(capacity), String.valueOf(periodMillis));
        return new long[] {(Long) r.get(0), (Long) r.get(1), (Long) r.get(2)};
    }

    @Override public void destroy() {
        connection.close();
        client.shutdown();
    }
}
