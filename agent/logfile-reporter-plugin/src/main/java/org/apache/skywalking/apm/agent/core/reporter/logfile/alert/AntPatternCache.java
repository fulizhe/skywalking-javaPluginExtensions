package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ant pattern 去重缓存：相同 pattern 字符串只编译一次。
 * <p>
 * 由 {@link RulesEngine} 实例持有，生命周期与引擎一致；单测经实例隔离，无需全局重置。
 * </p>
 */
final class AntPatternCache {

    private final ConcurrentHashMap<String, CompiledAntPattern> cache = new ConcurrentHashMap<String, CompiledAntPattern>();
    private final AtomicInteger compileCount = new AtomicInteger();

    CompiledAntPattern get(final String pattern) {
        return cache.computeIfAbsent(pattern, this::compileOnce);
    }

    int size() {
        return cache.size();
    }

    /** 单测断言"相同 pattern 只编译一次"用，生产代码勿调用 */
    int getCompileCountForTest() {
        return compileCount.get();
    }

    private CompiledAntPattern compileOnce(final String pattern) {
        compileCount.incrementAndGet();
        return CompiledAntPattern.compile(pattern);
    }
}
