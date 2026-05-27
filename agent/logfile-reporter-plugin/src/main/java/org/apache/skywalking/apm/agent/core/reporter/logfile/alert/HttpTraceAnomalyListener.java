package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.reporter.logfile.LogFileReporterPluginConfig;
import org.apache.skywalking.apm.dependencies.com.google.gson.Gson;

/**
 * 将慢/错链路事件 POST 到业务 Spring Boot 等 HTTP 端点，避免 ClassLoader 隔离问题。
 * <p>
 * URL 支持 {@code ${WebPort:9600}} 占位符，或由 {@code webhook_path} + 环境变量端口自动拼装。
 * </p>
 */
public class HttpTraceAnomalyListener implements TraceAnomalyListener {

    private static final ILog LOGGER = LogManager.getLogger(HttpTraceAnomalyListener.class);
    private static final Gson GSON = new Gson();

    private final String webhookUrlTemplate;
    private volatile String resolvedWebhookUrl;

    public HttpTraceAnomalyListener(final String webhookUrlTemplate) {
        this.webhookUrlTemplate = webhookUrlTemplate;
        final String previewResolved = webhookUrlTemplate != null && webhookUrlTemplate.indexOf("${") >= 0
                ? WebhookUrlResolver.resolveTemplate(webhookUrlTemplate)
                : webhookUrlTemplate;
        LOGGER.info("### [TraceAlert] HttpTraceAnomalyListener created, urlTemplate=[{}], resolvedUrl=[{}], "
                        + "connectTimeoutMs={}, readTimeoutMs={}",
                webhookUrlTemplate, previewResolved, resolveConnectTimeout(), resolveReadTimeout());
    }

    public static HttpTraceAnomalyListener fromConfig() {
        final String template = WebhookUrlResolver.getWebhookUrlTemplate();
        if (template == null || template.isEmpty()) {
            LOGGER.debug("### [TraceAlert] skip HttpTraceAnomalyListener: webhook template is empty.");
            return null;
        }
        return new HttpTraceAnomalyListener(template);
    }

    @Override
    public void onTraceAlert(final TraceAlertEvent event) {
        final String targetUrl = resolveWebhookUrl();
        if (targetUrl == null || targetUrl.isEmpty()) {
            LOGGER.warn("### Trace alert webhook URL is empty, skip trace [{}].", event.getTraceId());
            return;
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(targetUrl).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(resolveConnectTimeout());
            connection.setReadTimeout(resolveReadTimeout());
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            final byte[] body = GSON.toJson(event.toMap()).getBytes(StandardCharsets.UTF_8);
            connection.setRequestProperty("Content-Length", String.valueOf(body.length));
            final OutputStream outputStream = connection.getOutputStream();
            try {
                outputStream.write(body);
            } finally {
                outputStream.close();
            }

            final int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                LOGGER.warn("### Trace alert webhook returned non-success status [{}] for trace [{}], url [{}].",
                        status, event.getTraceId(), targetUrl);
            } else if (LOGGER.isDebugEnable()) {
                LOGGER.debug("### Trace alert webhook succeeded for trace [{}], url [{}].",
                        event.getTraceId(), targetUrl);
            }
        } catch (IOException e) {
            LOGGER.error(e, "### Failed to POST trace alert for trace [{}] to [{}].",
                    event.getTraceId(), targetUrl);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 每次回调时解析 URL，以便读取启动时注入的 {@code WebPort} 等环境变量。
     */
    private String resolveWebhookUrl() {
        if (webhookUrlTemplate == null || webhookUrlTemplate.isEmpty()) {
            return null;
        }
        if (webhookUrlTemplate.indexOf("${") >= 0) {
            return WebhookUrlResolver.resolveTemplate(webhookUrlTemplate);
        }
        if (resolvedWebhookUrl == null) {
            resolvedWebhookUrl = webhookUrlTemplate;
        }
        return resolvedWebhookUrl;
    }

    private int resolveConnectTimeout() {
        final Integer configured = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_CONNECT_TIMEOUT_MS;
        return configured != null && configured > 0 ? configured : 3000;
    }

    private int resolveReadTimeout() {
        final Integer configured = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_READ_TIMEOUT_MS;
        return configured != null && configured > 0 ? configured : 5000;
    }
}
