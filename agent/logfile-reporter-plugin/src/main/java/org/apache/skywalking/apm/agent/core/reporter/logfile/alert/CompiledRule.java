package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

/**
 * 启动期编译后的单条规则，热路径仅调用 {@link #matches(String, String)}。
 */
class CompiledRule {

    private final int index;
    private final String descriptor;
    private final MatchKind matchKind;
    private final RuleSyntax syntax;
    private final TracePatternMatcher matcher;

    CompiledRule(final int index, final String descriptor, final MatchKind matchKind, final RuleSyntax syntax,
            final TracePatternMatcher matcher) {
        this.index = index;
        this.descriptor = descriptor;
        this.matchKind = matchKind;
        this.syntax = syntax;
        this.matcher = matcher;
    }

    int getIndex() {
        return index;
    }

    String getDescriptor() {
        return descriptor;
    }

    MatchKind getMatchKind() {
        return matchKind;
    }

    RuleSyntax getSyntax() {
        return syntax;
    }

    boolean matches(final String operation, final String url) {
        if (matchKind == MatchKind.OPERATION) {
            return matcher.matches(operation);
        }
        return matcher.matches(UrlPathExtractor.extractPath(url));
    }
}
