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
 * <b>关于"连接数"这件事（2026-10-02 排查结论，勿再重复追查）</b>：
 * 曾怀疑本类每条告警新建一条连接、打满本机端口池，据此换过自带池化 HTTP 客户端 ——
 * 那次判断是错的（测量工具污染 + 探针打在 500 端点上自己 churn），<b>已回退</b>。
 * 真因是 <b>Tomcat 在 {@code prepareResponse()} 里按状态码强制发 {@code Connection: close}</b>
 * —— {@code Http11Processor.statusDropsConnection()}，8 个码
 * {@code {400,408,411,413,414,500,501,503}}（与 Apache httpd 同一份清单；<b>协议层行为，
 * 与错误派发无关</b>，404 不在其中）。那是<b>业务端</b>的性质，与本类无关。
 * 源码定位见 {@code docs/notes/2026-10-02-tcp-port-pool-exhaustion-and-a-measurement-trap.md} §2.2。
 * </p>
 * <p>
 * <b>但本类自身确实有个该修的问题</b>：每条告警都 {@code disconnect()}，等于每次都声明
 * "这条连接不会再被复用"，把 JDK 的 keep-alive 缓存清空 —— 告警一多就是**无上界的连接数**。
 * 去掉后连接被缓存复用，上界变成 JDK 的 {@code http.maxConnections}（默认 5）。
 * 实测：200 条告警，保留时 app 手里 0 条连接、去掉后 4~5 条，215 次投递全成功。
 * 详见 {@link #onTraceAlert} 末尾的注释。
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
            drainBody(connection, status);
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
        }
        // 刻意**不调** connection.disconnect()。
        //
        // JDK 对 disconnect() 的契约是"调用它即表示这条 Connection 不会再被复用"，而
        // HttpURLConnection 的隐式 keep-alive（sun.net.www.http.KeepAliveCache）正是靠连接用完
        // **留在缓存里**给下一次取用。逐条 disconnect 等于每次都把缓存清空。
        //
        // 实测（2026-10-02，判据是"app 手里有几条 ESTABLISHED"，不看会被测量工具污染的总数）：
        //   保留 disconnect()    → 200 条告警打完，app ESTABLISHED = 0（每条一条新建，用完即毁）
        //   去掉 disconnect()    → 200 条告警打完，app ESTABLISHED = **4~5**，且 215 次投递全成功
        // 5 恰为 JDK http.maxConnections 的默认值 —— 连接确实被缓存复用并握着。
        //
        // 收益：告警链路的连接数从"**无上界**（每条告警一条）"变成"**有上界（JDK 的
        // http.maxConnections，默认 5）**"，与告警量无关。高频错误下这正是"监控不得成为
        // 业务负担"需要的那条性质。
        //
        // 为什么能成立：webhook 打的是本机端点、响应是 200 + Content-Length，
        // 且 {@link #drainBody} 已把 body 读干净 —— 两者都满足才谈得上复用。
        // 代价：最多多留 5 条 socket 到 http.keepAlive.timeout 后由缓存自己回收，对进程无感。
    }

    /**
     * 读干净响应体，<b>让连接具备被 keep-alive 复用的资格</b>。
     *
     * <p>与"不调 {@code disconnect()}"是同一件事的两半：连接要留在
     * {@code KeepAliveCache} 里，前提是响应体已被读完 —— socket 里还有未消费的字节时，
     * JDK 判定该连接不可复用，<b>少任何一半复用都不成立</b>。
     *
     * <p>按状态分流：成功读 {@code getInputStream()}、非 2xx 读 {@code getErrorStream()}
     * —— 4xx/5xx 时前者直接抛 {@link IOException}，不分流就拿不到错误响应的 body。
     *
     * <p>读失败只吞不抛：body 读不出来属于"采集不到"，不该反过来再记一次投递失败。
     * 64KB 上限是防御"对端不回 EOF"，每 socket 另有 readTimeout 兜底。
     */
    private static void drainBody(final HttpURLConnection connection, final int status) {
        try (java.io.InputStream in = status >= 200 && status < 400
                ? connection.getInputStream() : connection.getErrorStream()) {
            if (in == null) {
                return;
            }
            final byte[] buf = new byte[512];
            int total = 0;
            while (total < 64 * 1024) {
                final int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                total += n;
            }
        } catch (IOException e) {
            LOGGER.debug("### [TraceAlert] drain webhook response body failed (ignored): {}", e.getMessage());
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
