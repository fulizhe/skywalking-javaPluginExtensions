package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

/**
 * 启动期预编译的路径匹配器，热路径仅调用 {@link #matches(String)}。
 */
interface TracePatternMatcher {

    boolean matches(String text);
}
