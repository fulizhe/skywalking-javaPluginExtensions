package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局 Ant pattern 缓存：相同 pattern 字符串只编译一次。
 */
final class AntPatternCache {

    private static final ConcurrentHashMap<String, CompiledAntPattern> CACHE = new ConcurrentHashMap<String, CompiledAntPattern>();
    private static final AtomicInteger COMPILE_COUNT = new AtomicInteger();

    private AntPatternCache() {
    }

    static CompiledAntPattern get(final String pattern) {
        return CACHE.computeIfAbsent(pattern, AntPatternCache::compileOnce);
    }

    static int cacheSize() {
        return CACHE.size();
    }

    /** 单测重置，生产代码勿调用。 */
    static void resetForTest() {
        CACHE.clear();
        COMPILE_COUNT.set(0);
    }

    /** 单测观测 compile 次数，生产代码勿调用。 */
    static int getCompileCountForTest() {
        return COMPILE_COUNT.get();
    }

    private static CompiledAntPattern compileOnce(final String pattern) {
        COMPILE_COUNT.incrementAndGet();
        return CompiledAntPattern.compile(pattern);
    }
}
