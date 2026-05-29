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
        TraceAlertMetrics.get().recordHttpAttempt();

        final String targetUrl = resolveWebhookUrl();
        if (targetUrl == null || targetUrl.isEmpty()) {
            TraceAlertMetrics.get().recordHttpSkippedEmptyUrl();
            LOGGER.warn("### [TraceAlert] webhook URL is empty, skip trace [{}].", event.getTraceId());
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
                TraceAlertMetrics.get().recordHttpFailure(event.getTraceId(), targetUrl, status,
                        "HTTP status " + status);
                LOGGER.warn("### [TraceAlert] webhook non-success status [{}] for trace [{}], url [{}].",
                        status, event.getTraceId(), targetUrl);
            } else {
                TraceAlertMetrics.get().recordHttpSuccess(event.getTraceId(), targetUrl);
                if (LOGGER.isDebugEnable()) {
                    LOGGER.debug("### [TraceAlert] webhook succeeded for trace [{}], url [{}].",
                            event.getTraceId(), targetUrl);
                }
            }
        } catch (IOException e) {
            TraceAlertMetrics.get().recordHttpFailure(event.getTraceId(), targetUrl, 0, e.getMessage());
            LOGGER.error(e, "### [TraceAlert] failed to POST trace alert for trace [{}] to [{}].",
                    event.getTraceId(), targetUrl);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 解析 webhook 目标 URL。
     * <p>
     * {@code ${WebPort}} 等占位符从进程环境变量读取；本 Listener 在 Agent 启动早期即创建，
     * 早于部分业务进程完成环境变量注入。因此采用「解析成功则缓存、未展开则重试」：
     * </p>
     * <ul>
     *   <li>已缓存完整 URL（不含 {@code ${}）时直接返回，避免每条告警重复解析；</li>
     *   <li>含占位符的模板在每次回调时尝试 {@link WebhookUrlResolver#resolveTemplate}，
     *       直至得到不含 {@code ${}} 的 URL 后写入 {@link #resolvedWebhookUrl}；</li>
     *   <li>无占位符的固定 URL 在首次访问时缓存。</li>
     * </ul>
     * <p>
     * 部署侧应通过环境变量提供 {@code WebPort}（如 {@code ${WebPort:9600}} 中的变量名），
     * 保证首次告警触发前变量已就绪，以便尽快命中缓存。
     * </p>
     */
    private String resolveWebhookUrl() {
        if (webhookUrlTemplate == null || webhookUrlTemplate.isEmpty()) {
            return null;
        }
        final String cached = resolvedWebhookUrl;
        if (cached != null) {
            return cached;
        }
        final String resolved = webhookUrlTemplate.indexOf("${") >= 0
                ? WebhookUrlResolver.resolveTemplate(webhookUrlTemplate)
                : webhookUrlTemplate;
        if (resolved != null && !resolved.isEmpty() && resolved.indexOf("${") < 0) {
            resolvedWebhookUrl = resolved;
        }
        return resolved;
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
