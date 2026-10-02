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
 * <p>
 * <b>关于"每条告警一条 TCP 连接"这件事（2026-10-02 排查结论，勿再重复追查）</b>：
 * 曾怀疑本类每条告警新建一条连接、高频告警下打满本机端口池（13,977），并为此换过自带池化
 * HTTP 客户端 —— <b>该结论已被证伪，池化方案已回退</b>。
 * </p>
 * <p>
 * 干净的对照实验（单线程 + 池大小 1 的进程内探针发 200 次请求，负载生成器自身最多占 1 条连接）：
 * <pre>
 *   ① /hello（200，不产生告警）                        => 新增连接   2 条
 *   ② /status/500（500，命中 error_ignore 规则，无告警） => 新增连接 252 条
 *   ③ /api/trace-alert-demo/error（500，产生告警）      => 新增连接 235 条
 * </pre>
 * ②与③几乎相同、而②<b>根本没有告警</b> —— 说明连接增长与告警无关。
 * 真因是响应头：<b>Tomcat 对走 {@code /error} 错误派发的 5xx 响应强制发
 * {@code Connection: close}</b>（对比 200 响应带 {@code Content-Length}），于是
 * <b>每个 5xx 请求泄漏一条 TCP 连接</b>。压测路径集里 5xx 占比高（默认档 2/5、
 * {@code -AllEndpoints} 8/60），在数百 rps 下即产生上百条连接/秒，远超
 * {@code 13977/120 ≈ 116} 条/秒的填池阈值。
 * </p>
 * <p>
 * 因此本类维持最朴素的 {@code HttpURLConnection} + 逐条 {@code disconnect()}：
 * <b>它不是缺陷所在</b>，换成显式池化也不能改变 5xx 响应带 {@code Connection: close}
 * 这一事实（对照实验里三种客户端写法 —— 保留 disconnect / 去掉 disconnect + 排空 body /
 * 显式池化 —— 分别得到 202 / 207 / 207 条连接，无差别）。
 * 若日后要让 5xx 响应也可复用连接，该改的是<b>服务端</b>（让控制器直接返回
 * {@code ResponseEntity.status(500)} 而非抛异常走 ERROR 派发），不是本类。
 * 排查经过与可复现命令见 {@code docs/notes/2026-10-02-tcp-port-pool-exhaustion-and-a-measurement-trap.md}。
 * </p>
 */
class HttpTraceAnomalyListener implements TraceAnomalyListener {

    private static final ILog LOGGER = LogManager.getLogger(HttpTraceAnomalyListener.class);
    private static final Gson GSON = new Gson();

    private final String webhookUrlTemplate;
    private volatile String resolvedWebhookUrl;

    HttpTraceAnomalyListener(final String webhookUrlTemplate) {
        this.webhookUrlTemplate = webhookUrlTemplate;
        final String previewResolved = webhookUrlTemplate != null && webhookUrlTemplate.indexOf("${") >= 0
                ? WebhookUrlResolver.resolveTemplate(webhookUrlTemplate)
                : webhookUrlTemplate;
        LOGGER.info("### [TraceAlert] HttpTraceAnomalyListener created, urlTemplate=[{}], resolvedUrl=[{}], "
                        + "connectTimeoutMs={}, readTimeoutMs={}",
                webhookUrlTemplate, previewResolved, resolveConnectTimeout(), resolveReadTimeout());
    }

    static HttpTraceAnomalyListener fromConfig() {
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
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body);
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
