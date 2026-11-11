package com.sb24.api.redis;

/** The few Redis operations the app needs. Two implementations: Lettuce (real Redis) and in-memory (single node). */
public interface RedisOps {
    boolean setIfAbsent(String key, String value, long ttlMillis);
    void set(String key, String value, long ttlMillis);
    boolean exists(String key);
    void deleteIfEquals(String key, String value);

    /** True when token buckets can be evaluated atomically server-side (shared across instances). */
    boolean supportsDistributedBuckets();

    /** @return {allowed (1/0), remainingTokens, retryAfterMillis} */
    long[] tokenBucket(String key, int capacity, long periodMillis);
}
