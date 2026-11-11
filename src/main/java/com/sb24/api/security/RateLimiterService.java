package com.sb24.api.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sb24.api.config.AppProperties;
import com.sb24.api.redis.RedisOps;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Token-bucket rate limiting. With Redis enabled the bucket lives in Redis (one atomic Lua script) so the limit is
 * global across API instances. Without Redis, or if Redis is unreachable, it falls back to per-instance buckets
 * (Bucket4j in a bounded Caffeine cache) so the API stays available and still protected, just with a looser global cap.
 */
@Service
public class RateLimiterService {
    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    public record Decision(boolean allowed, long remaining, long retryAfterMillis) {}

    private final RedisOps redis;
    private final Cache<String, Bucket> local = Caffeine.newBuilder()
            .maximumSize(200_000)
            .expireAfterAccess(Duration.ofMinutes(15))
            .build();

    public RateLimiterService(RedisOps redis) { this.redis = redis; }

    public Decision consume(String key, AppProperties.Limit limit) {
        if (redis.supportsDistributedBuckets()) {
            try {
                long[] r = redis.tokenBucket("rl:" + key, limit.capacity(), limit.period().toMillis());
                return new Decision(r[0] == 1, r[1], r[2]);
            } catch (RuntimeException e) {
                log.error("Redis rate limiter unavailable, using local buckets: {}", e.toString());
            }
        }
        Bucket bucket = local.get(key, k -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(limit.capacity()).refillGreedy(limit.capacity(), limit.period()).build())
                .build());
        ConsumptionProbe p = bucket.tryConsumeAndReturnRemaining(1);
        return new Decision(p.isConsumed(), p.getRemainingTokens(), p.getNanosToWaitForRefill() / 1_000_000);
    }
}
