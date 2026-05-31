package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

/**
 * Ant 路径匹配，语义对齐 Spring {@code AntPathMatcher}（{@code ?} / {@code *} / {@code **}）。
 * 参考 SkyWalking trace-ignore-plugin {@code FastPathMatcher}。
 */
final class FastPathAntMatcher {

    private FastPathAntMatcher() {
    }

    static boolean match(final String pattern, final String path) {
        if (pattern == null || path == null) {
            return false;
        }
        return normalMatch(pattern, 0, path, 0);
    }

    private static boolean normalMatch(final String pat, int p, final String str, int s) {
        while (p < pat.length()) {
            final char pc = pat.charAt(p);
            final char sc = safeCharAt(str, s);

            if (pc == '*') {
                p++;
                if (safeCharAt(pat, p) == '*') {
                    p++;
                    return multiWildcardMatch(pat, p, str, s);
                }
                return wildcardMatch(pat, p, str, s);
            }

            if (pc == '?' && sc != 0 && sc != '/' || pc == sc) {
                s++;
                p++;
                continue;
            }
            return false;
        }
        return s == str.length();
    }

    private static boolean wildcardMatch(final String pat, int p, final String str, int s) {
        final char pc = safeCharAt(pat, p);

        if (pc == 0) {
            while (true) {
                final char sc = safeCharAt(str, s);
                if (sc == 0) {
                    return true;
                }
                if (sc == '/') {
                    return s == str.length() - 1;
                }
                s++;
            }
        }

        while (true) {
            final char sc = safeCharAt(str, s);

            if (sc == '/') {
                if (pc == sc) {
                    return normalMatch(pat, p + 1, str, s + 1);
                }
                return false;
            }

            if (!normalMatch(pat, p, str, s)) {
                if (s >= str.length()) {
                    return false;
                }
                s++;
                continue;
            }
            return true;
        }
    }

    private static boolean multiWildcardMatch(final String pat, int p, final String str, int s) {
        switch (safeCharAt(pat, p)) {
            case 0:
                return true;
            case '/':
                p++;
                break;
            default:
                break;
        }

        while (true) {
            if (!normalMatch(pat, p, str, s)) {
                if (s >= str.length()) {
                    return false;
                }
                s++;
                continue;
            }
            return true;
        }
    }

    private static char safeCharAt(final String value, final int index) {
        if (index >= value.length()) {
            return 0;
        }
        return value.charAt(index);
    }
}
