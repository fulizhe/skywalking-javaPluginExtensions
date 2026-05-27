package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import org.apache.skywalking.apm.agent.core.reporter.logfile.LogFileReporterPluginConfig;
import org.apache.skywalking.apm.dependencies.com.google.common.cache.Cache;
import org.apache.skywalking.apm.dependencies.com.google.common.cache.CacheBuilder;

/**
 * 同一 traceId 的慢/错告警去重缓存，基于 Guava Cache 自动过期，避免 map 无限增长。
 */
final class NotifiedFlagsCache {

    private static final int FLAG_ERROR = 1;
    private static final int FLAG_SLOW = 2;
    private static final long DEFAULT_TTL_MS = 600_000L;

    private final long ttlMs;
    private final Cache<String, Integer> cache;

    NotifiedFlagsCache(final long ttlMs) {
        this.ttlMs = ttlMs > 0 ? ttlMs : DEFAULT_TTL_MS;
        this.cache = CacheBuilder.newBuilder()
                .expireAfterWrite(this.ttlMs, TimeUnit.MILLISECONDS)
                .build();
    }

    static NotifiedFlagsCache fromConfig() {
        final Integer configured = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.NOTIFIED_CACHE_TTL_MS;
        final long ttlMs = configured != null && configured > 0 ? configured.longValue() : DEFAULT_TTL_MS;
        return new NotifiedFlagsCache(ttlMs);
    }

    boolean shouldNotify(final String traceId, final AlertType type) {
        final Integer flags = cache.getIfPresent(traceId);
        if (flags == null) {
            return true;
        }
        final int flag = type == AlertType.ERROR ? FLAG_ERROR : FLAG_SLOW;
        return (flags.intValue() & flag) == 0;
    }

    void markNotified(final String traceId, final EnumSet<AlertType> types) {
        Integer existing = cache.getIfPresent(traceId);
        int flags = existing != null ? existing.intValue() : 0;
        for (AlertType type : types) {
            flags |= type == AlertType.ERROR ? FLAG_ERROR : FLAG_SLOW;
        }
        cache.put(traceId, Integer.valueOf(flags));
    }

    int size() {
        cache.cleanUp();
        return (int) cache.size();
    }

    long getTtlMs() {
        return ttlMs;
    }
}
