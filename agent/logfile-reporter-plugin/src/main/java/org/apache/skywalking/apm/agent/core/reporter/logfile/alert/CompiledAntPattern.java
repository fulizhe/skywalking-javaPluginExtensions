package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

/**
 * 启动期编译的 Ant pattern，进程生命周期内不可变，热路径无解析/编译。
 */
final class CompiledAntPattern {

    private final String pattern;

    private CompiledAntPattern(final String pattern) {
        this.pattern = pattern;
    }

    static CompiledAntPattern compile(final String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("Ant pattern must not be empty");
        }
        return new CompiledAntPattern(pattern);
    }

    String getPattern() {
        return pattern;
    }

    boolean matches(final String text) {
        if (text == null) {
            return false;
        }
        return FastPathAntMatcher.match(pattern, text);
    }
}
