package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

/**
 * Ant 语法匹配器，内部通过 {@link AntPatternCache} 复用 {@link CompiledAntPattern}。
 */
final class AntTracePatternMatcher implements TracePatternMatcher {

    private final CompiledAntPattern compiled;

    AntTracePatternMatcher(final String pattern) {
        this.compiled = AntPatternCache.get(pattern);
    }

    @Override
    public boolean matches(final String text) {
        return compiled.matches(text);
    }
}
