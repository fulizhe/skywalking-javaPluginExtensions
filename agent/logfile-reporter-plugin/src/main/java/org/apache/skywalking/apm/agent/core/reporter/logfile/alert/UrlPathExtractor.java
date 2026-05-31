package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.net.URI;

/**
 * 从 span {@code url} tag 提取 path，供 {@code url:} 规则匹配。
 */
final class UrlPathExtractor {

    private UrlPathExtractor() {
    }

    static String extractPath(final String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        final String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("/")) {
            return stripQuery(trimmed);
        }
        try {
            final URI uri = URI.create(trimmed);
            final String path = uri.getPath();
            if (path != null && !path.isEmpty()) {
                return path;
            }
            if (uri.getScheme() == null) {
                return stripQuery(trimmed.startsWith("/") ? trimmed : "/" + trimmed);
            }
            return "";
        } catch (IllegalArgumentException ignored) {
            final int schemeEnd = trimmed.indexOf("://");
            if (schemeEnd > 0) {
                final int pathStart = trimmed.indexOf('/', schemeEnd + 3);
                if (pathStart >= 0) {
                    return stripQuery(trimmed.substring(pathStart));
                }
            }
            return stripQuery(trimmed.startsWith("/") ? trimmed : "/" + trimmed);
        }
    }

    private static String stripQuery(final String pathWithOptionalQuery) {
        final int queryIndex = pathWithOptionalQuery.indexOf('?');
        if (queryIndex >= 0) {
            return pathWithOptionalQuery.substring(0, queryIndex);
        }
        return pathWithOptionalQuery;
    }
}
