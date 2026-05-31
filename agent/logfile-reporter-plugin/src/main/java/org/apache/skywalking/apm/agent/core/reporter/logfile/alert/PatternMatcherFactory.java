package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

/**
 * 按 Ant 语法创建启动期预编译 Matcher。
 */
final class PatternMatcherFactory {

    private PatternMatcherFactory() {
    }

    static TracePatternMatcher createAnt(final String pattern) {
        return new AntTracePatternMatcher(pattern);
    }
}
