package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.util.HashMap;
import java.util.Map;

import org.apache.skywalking.apm.agent.core.reporter.logfile.LogFileReporterPluginConfig;

/**
 * 解析 webhook URL 中的 {@code ${ENV}} / {@code ${ENV:default}} 占位符，
 * 以及由 host + 环境变量端口 + path 拼装完整地址。
 */
final class WebhookUrlResolver {

    private interface EnvProvider {
        String get(String name);
    }

    private static EnvProvider envProvider = new EnvProvider() {
        @Override
        public String get(final String name) {
            return System.getenv(name);
        }
    };

    private WebhookUrlResolver() {
    }

    /**
     * 单测注入环境变量，模拟用户启动时设置的 WebPort 等。
     * 传入 {@code null} 或空 Map 表示不注入任何环境变量。
     */
    static void setEnvForTest(final Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            envProvider = new EnvProvider() {
                @Override
                public String get(final String name) {
                    return null;
                }
            };
            return;
        }
        final Map<String, String> copy = new HashMap<String, String>(env);
        envProvider = new EnvProvider() {
            @Override
            public String get(final String name) {
                return copy.get(name);
            }
        };
    }

    static void resetEnvProviderForTest() {
        envProvider = new EnvProvider() {
            @Override
            public String get(final String name) {
                return System.getenv(name);
            }
        };
    }

    /**
     * 返回配置的 webhook URL 模板（仍含 {@code ${...}} 占位符），无法配置时返回 null。
     */
    static String getWebhookUrlTemplate() {
        final String configured = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_URL;
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        final String path = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_PATH;
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        return buildPathOnlyTemplate(path.trim());
    }

    /**
     * 解析为最终可请求的 URL。
     */
    static String resolveConfiguredUrl() {
        final String template = getWebhookUrlTemplate();
        if (template == null || template.isEmpty()) {
            return null;
        }
        if (template.indexOf("${") >= 0) {
            return resolveTemplate(template);
        }
        return template;
    }

    static String buildPathOnlyTemplate(final String path) {
        final String envName = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_PORT_ENV;
        final Integer defaultPort = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_PORT_DEFAULT;
        final int fallback = defaultPort != null && defaultPort > 0 ? defaultPort : 9600;
        final String host = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_HOST;
        final String resolvedHost = host == null || host.trim().isEmpty() ? "127.0.0.1" : host.trim();
        final String envKey = envName == null || envName.trim().isEmpty() ? "WebPort" : envName.trim();
        final String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return "http://" + resolvedHost + ":${" + envKey + ":" + fallback + "}" + normalizedPath;
    }

    /**
     * 将模板中的 {@code ${WebPort:9600}} 等占位符替换为环境变量或默认值。
     */
    static String resolveTemplate(final String template) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        final StringBuilder result = new StringBuilder();
        int index = 0;
        while (index < template.length()) {
            final int start = template.indexOf("${", index);
            if (start < 0) {
                result.append(template.substring(index));
                break;
            }
            result.append(template.substring(index, start));
            final int end = template.indexOf('}', start + 2);
            if (end < 0) {
                result.append(template.substring(start));
                break;
            }
            final String placeholder = template.substring(start + 2, end);
            result.append(resolvePlaceholder(placeholder));
            index = end + 1;
        }
        return result.toString();
    }

    private static String resolvePlaceholder(final String placeholder) {
        final int colonIndex = placeholder.indexOf(':');
        final String envName;
        final String defaultValue;
        if (colonIndex >= 0) {
            envName = placeholder.substring(0, colonIndex).trim();
            defaultValue = placeholder.substring(colonIndex + 1);
        } else {
            envName = placeholder.trim();
            defaultValue = "";
        }
        final String envValue = envProvider.get(envName);
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue.trim();
        }
        return defaultValue;
    }
}
