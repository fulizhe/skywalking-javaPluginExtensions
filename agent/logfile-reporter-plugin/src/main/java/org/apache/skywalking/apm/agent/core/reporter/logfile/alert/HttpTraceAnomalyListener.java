package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
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
 * <b>为什么自带池化 HTTP 客户端而不用 {@code java.net.HttpURLConnection}</b>：
 * 后者的连接复用依赖 JDK 内部的 {@code KeepAliveCache}（隐式、无显式上界）。实测在 agent 的
 * {@code PluginClassLoader} 运行时里<b>该缓存不生效</b> —— 空闲应用、串行 200 个错误请求
 * （各触发 1 条 ERROR 告警）产生 <b>213 条新 TCP 连接</b>，而<b>完全相同的代码在进程外
 * 复用率为 100%</b>。高频错误下（本机 3 分钟 1.2 万条告警）新建连接撞满 Windows 默认的
 * 13,977 个动态端口，此后<b>本机任何</b>新建连接都失败（{@code errno 10022} /
 * "Invalid argument: connect"），表现为业务页面加载不出 js/css —— <b>监控把业务打挂了</b>，
 * 违背「监控只能是助力，而不是阻碍」。已排除的假设：JDK 版本（8 与 17 同样失效）、
 * {@code http.*} 系统属性（未设置）、负载压力（进程外在负载下也正常复用）。
 * 决策与取舍见 {@code docs/adr/adr-05-alert-webhook-uses-pooled-http-client.md}。
 * </p>
 */
class HttpTraceAnomalyListener implements TraceAnomalyListener {

    private static final ILog LOGGER = LogManager.getLogger(HttpTraceAnomalyListener.class);
    private static final Gson GSON = new Gson();

    /**
     * 全插件共享的池化客户端。<b>静态单例是刻意的</b>：每条告警各建一个 client 会让
     * "连接数有上界"这个性质失效（连接池随告警数增长），而这正是本次要根除的问题。
     * <p>
     * 线程安全：{@link CloseableHttpClient} 可多线程共用；消费侧本就是单线程
     * （{@code AsyncTraceAlertDispatcher} 的 {@code LogfileTraceAlert}），此处不额外加锁。
     * </p>
     * <p>
     * 池上限故意定得很小：单线程顺序发请求，2~4 条足够；定小是为了让"连接数有硬上界"
     * 成为<b>可验证的性质</b>，而不是取决于对端行为。超限时
     * {@code PoolingHttpClientConnectionManager} 会阻塞等待（默认 3s）后抛
     * {@code ConnectionPoolTimeoutException}，被下面的 catch 计入失败 —— 宁可少投一条，
     * 也不给本机端口池加压。
     * </p>
     */
    private static final CloseableHttpClient HTTP_CLIENT = buildClient();

    private static CloseableHttpClient buildClient() {
        try {
            final PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
            cm.setMaxTotal(4);
            cm.setDefaultMaxPerRoute(4);
            final RequestConfig cfg = RequestConfig.custom()
                    .setConnectTimeout(resolveConnectTimeout())
                    // 取连接也要有界：池满时宁可等一下再放弃，也不要无限建连。
                    // 这个值与 connect 同量级即可 —— 池只有 4 条，正常情况下永远等不到。
                    .setConnectionRequestTimeout(resolveConnectTimeout())
                    .setSocketTimeout(resolveReadTimeout())
                    .build();
            final CloseableHttpClient client = HttpClients.custom()
                    .setConnectionManager(cm)
                    .setDefaultRequestConfig(cfg)
                    // 默认重试对 POST 是危险的：业务端点可能已经收到并处理了请求，
                    // 重试会造成重复告警。宁可不重试。
                    .disableAutomaticRetries()
                    .build();
            LOGGER.info("### [TraceAlert] pooled webhook client initialized, maxTotal=4, "
                    + "connectTimeoutMs={}, readTimeoutMs={}", resolveConnectTimeout(), resolveReadTimeout());
            return client;
        } catch (RuntimeException e) {
            // 走到这里说明连池化客户端都建不起来 —— 记一次失败并退化,null 表示"本进程不可用",
            // 后续每条告警都只计数不投递(见 onTraceAlert 的 null 判断)。监控不得抛异常打断业务。
            LOGGER.error(e, "### [TraceAlert] failed to build pooled webhook client, webhook disabled.");
            return null;
        }
    }

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
        final CloseableHttpClient client = HTTP_CLIENT;
        if (client == null) {
            // buildClient() 失败:只计数,不投递。宁可一条告警都不发,也不给业务添乱。
            TraceAlertMetrics.get().recordHttpFailure(event.getTraceId(), null, 0,
                    "pooled webhook client unavailable");
            return;
        }
        TraceAlertMetrics.get().recordHttpAttempt();

        final String targetUrl = resolveWebhookUrl();
        if (targetUrl == null || targetUrl.isEmpty()) {
            TraceAlertMetrics.get().recordHttpSkippedEmptyUrl();
            LOGGER.warn("### [TraceAlert] webhook URL is empty, skip trace [{}].", event.getTraceId());
            return;
        }

        try {
            final byte[] body = GSON.toJson(event.toMap()).getBytes(StandardCharsets.UTF_8);
            final HttpPost post = new HttpPost(targetUrl);
            post.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
            try (CloseableHttpResponse response = client.execute(post)) {
                final int status = response.getStatusLine().getStatusCode();
                // 读完 body 才会把连接还回池里。用 consume() 而非 close()：前者确保把流读干净，
                // 只 close 会让这条连接在池里变成半死状态。内容本身不关心(webhook 的响应不用看)。
                EntityUtils.consumeQuietly(response.getEntity());
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
            }
        } catch (IOException e) {
            TraceAlertMetrics.get().recordHttpFailure(event.getTraceId(), targetUrl, 0, e.getMessage());
            LOGGER.error(e, "### [TraceAlert] failed to POST trace alert for trace [{}] to [{}].",
                    event.getTraceId(), targetUrl);
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

    private static int resolveConnectTimeout() {
        final Integer configured = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_CONNECT_TIMEOUT_MS;
        return configured != null && configured > 0 ? configured : 3000;
    }

    private static int resolveReadTimeout() {
        final Integer configured = LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_READ_TIMEOUT_MS;
        return configured != null && configured > 0 ? configured : 5000;
    }
}
