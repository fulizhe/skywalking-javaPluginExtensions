package org.openskywalking.demo.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * demo-app HTTP 压测（自包含）：支持三种模式，用于填充 Trace 指标 / 告警 / profile 采样，
 * 以及**持续运行下的插件稳定性观测**（周期性打印插件自身计数与 JVM 堆）。
 *
 * <p><b>默认不参与 {@code mvn test}</b>：需显式 {@code -Dloadtest=true} 才执行。目标应用须先启动。</p>
 *
 * <p>模式（互斥，见 {@code loadtest.durationSec}）：</p>
 * <ul>
 *   <li><b>请求数模式</b>（默认）：发够 {@code loadtest.requests} 个请求即结束。</li>
 *   <li><b>时长模式</b>：{@code -Dloadtest.durationSec=N}（N&gt;0），持续压 N 秒后优雅结束。</li>
 *   <li><b>无限模式</b>：{@code -Dloadtest.durationSec=-1}，死循环直到进程被杀（Ctrl+C 或 Stop-Process）。</li>
 * </ul>
 *
 * <p>可覆盖的系统属性：</p>
 * <ul>
 *   <li>{@code -Dloadtest.baseUrl=http://127.0.0.1:9600}</li>
 *   <li>{@code -Dloadtest.paths=/hello,/fullSample,...}（逗号分隔，随机命中）</li>
 *   <li>{@code -Dloadtest.requests=1000}（请求数模式的任务总数）</li>
 *   <li>{@code -Dloadtest.threads=8}</li>
 *   <li>{@code -Dloadtest.timeoutMs=10000}</li>
 *   <li>{@code -Dloadtest.durationSec}（见上；缺省＝请求数模式）</li>
 *   <li>{@code -Dloadtest.progressSec=10}（持续模式下打印进度/插件计数的间隔）</li>
 *   <li>{@code -Dloadtest.metricsUrl=}&lt;baseUrl&gt;/inner/sw/metrics（缺省自动拼）</li>
 * </ul>
 *
 * <p>运行示例（建议经 {@code scripts/stress.ps1}）：</p>
 * <pre>
 * # 持续 1 小时
 * mvn -o -f agent/demo-app/pom.xml test -Dtest=HttpLoadTest -Dloadtest=true -Dloadtest.durationSec=3600
 * # 无限,直到 Ctrl+C
 * mvn -o -f agent/demo-app/pom.xml test -Dtest=HttpLoadTest -Dloadtest=true -Dloadtest.durationSec=-1
 * </pre>
 */
@EnabledIfSystemProperty(named = "loadtest", matches = "true")
public class HttpLoadTest {

    private static final String BASE_URL = stripTrailingSlash(prop("loadtest.baseUrl", "http://127.0.0.1:9600"));
    private static final int NUM_THREADS = Integer.parseInt(prop("loadtest.threads", "8"));
    private static final int TIMEOUT_MS = Integer.parseInt(prop("loadtest.timeoutMs", "10000"));
    private static final int PROGRESS_SEC = Integer.parseInt(prop("loadtest.progressSec", "10"));
    private static final String METRICS_URL = prop("loadtest.metricsUrl", BASE_URL + "/inner/sw/metrics");

    /** 每线程延迟样本上限（蓄水池），保证无限模式下内存有界。 */
    private static final int SAMPLES_PER_THREAD = 50_000;

    /**
     * 默认混合集 = 毫秒级正常端点 + 错误端点（<b>刻意不含慢端点</b>）。
     *
     * <p>{@code /fullSample}（默认 deps=true，单请求 5.5s，中间件未全起时约 8s）与
     * {@code /longTimeTask}（固定 sleep 0.5s）会把延迟均值拉到 ~800ms、吞吐掉一个数量级
     * （8 线程 ~10 rps vs ~800 rps）。两条都在 {@code scripts/stress-slow.ps1} 里，
     * 那一档才是"压慢端点"的地方；依赖面造数用 {@code stress.ps1 -WithDeps}。
     *
     * <p>要连慢端点一起压（如为 slow 统计列表造数）走
     * {@code deploy/deploy_remote.py} 的远程压测档，那份路径清单是独立硬编码的。
     */
    private static final String[] DEFAULT_PATHS = {
        "/hello",
        "/queryDbByMybatis",
        "/queryDbByJdbc",
        "/api/trace-alert-demo/error",
        "/api/trace-alert-demo/http500"
    };
    private static final String[] PATHS = prop("loadtest.paths", "").isEmpty()
        ? DEFAULT_PATHS : splitPaths(prop("loadtest.paths", ""));

    private static final String DURATION_PROP = prop("loadtest.durationSec", "");
    private static final boolean DURATION_MODE = !DURATION_PROP.isEmpty();
    private static final long DURATION_SEC = DURATION_MODE ? Long.parseLong(DURATION_PROP) : 0L;
    private static final boolean INFINITE = DURATION_MODE && DURATION_SEC <= 0L;
    private static final int NUM_REQUESTS = Integer.parseInt(prop("loadtest.requests", "1000"));

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger index = new AtomicInteger(0);
    private final AtomicLong seq = new AtomicLong(0);
    private final AtomicLong success = new AtomicLong(0);
    private final AtomicLong statusError = new AtomicLong(0);
    private final AtomicLong exceptions = new AtomicLong(0);
    private final Map<Integer, AtomicLong> statusCounts = new ConcurrentHashMap<Integer, AtomicLong>();
    private final List<Worker> workers = Collections.synchronizedList(new ArrayList<Worker>());
    private final long wallStart = System.currentTimeMillis();

    @Test
    public void testHttpLoad() throws InterruptedException {
        printHeader();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() { running.set(false); }
        }, "loadtest-shutdown"));

        final PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
        connManager.setMaxTotal(NUM_THREADS);
        connManager.setDefaultMaxPerRoute(NUM_THREADS);
        final RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(TIMEOUT_MS).setConnectionRequestTimeout(TIMEOUT_MS).setSocketTimeout(TIMEOUT_MS).build();
        final CloseableHttpClient httpClient = HttpClients.custom()
            .setConnectionManager(connManager).setDefaultRequestConfig(requestConfig).build();

        final CountDownLatch startGate = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        for (int t = 0; t < NUM_THREADS; t++) {
            final Worker worker = new Worker(httpClient, startGate);
            workers.add(worker);
            executor.submit(worker);
        }
        startGate.countDown();

        final Thread progress = new Thread(new Runnable() {
            @Override public void run() { progressLoop(); }
        }, "loadtest-progress");
        progress.setDaemon(true);
        progress.start();

        if (DURATION_MODE && !INFINITE) {
            // 时长模式：压 DURATION_SEC 秒后收工
            long deadline = wallStart + DURATION_SEC * 1000L;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500L);
            }
            running.set(false);
        } else if (INFINITE) {
            System.out.println("[load] 无限模式：持续压测中，Ctrl+C / Stop-Process 结束。");
            while (running.get()) {
                Thread.sleep(1000L);
            }
        }
        // 请求数模式：worker 各自发够即退出，无需在这里置 running

        executor.shutdown();
        executor.awaitTermination(24, TimeUnit.HOURS);
        running.set(false);
        progress.interrupt();

        try {
            httpClient.close();
            connManager.close();
        } catch (IOException ignored) {
            // 收尾
        }

        final long wallMs = System.currentTimeMillis() - wallStart;
        printReport(wallMs, success.get(), statusError.get(), exceptions.get(), statusCounts, latencyMillis());

        if (!INFINITE) {
            assertEquals(0L, exceptions.get(),
                "出现网络异常(" + exceptions.get() + "):请确认应用已启动且 baseUrl 正确(" + BASE_URL + ")");
            assertTrue(success.get() > 0, "没有任何 2xx 成功响应，请检查目标应用");
            if (!DURATION_MODE) {
                assertTrue(index.get() >= NUM_REQUESTS, "请求派发不足: " + index.get() + " < " + NUM_REQUESTS);
            }
        }
    }

    private void printHeader() {
        System.out.println("========================================================");
        System.out.println(" demo-app HTTP 压测");
        System.out.printf("   baseUrl   = %s%n", BASE_URL);
        System.out.printf("   paths     = %s%n", java.util.Arrays.toString(PATHS));
        System.out.printf("   threads   = %d   timeout = %dms%n", NUM_THREADS, TIMEOUT_MS);
        if (DURATION_MODE) {
            System.out.printf("   mode      = %s (durationSec=%d)%n", INFINITE ? "无限" : "时长", DURATION_SEC);
        } else {
            System.out.printf("   mode      = 请求数 (requests=%d)%n", NUM_REQUESTS);
        }
        System.out.printf("   metricsUrl= %s   progress=%ds%n", METRICS_URL, PROGRESS_SEC);
        System.out.println("========================================================");
    }

    /** 每 PROGRESS_SEC 打印一次：吞吐 + 插件自身计数(稳定性关键) + JVM 堆。 */
    private void progressLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(PROGRESS_SEC * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            if (!running.get()) {
                return;
            }
            final long elapsedMs = System.currentTimeMillis() - wallStart;
            final long done = success.get() + statusError.get() + exceptions.get();
            final Runtime rt = Runtime.getRuntime();
            final long heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
            System.out.printf("[progress] t=%ds  req=%d  rps=%.1f  2xx=%d  non2xx=%d  exc=%d  heap=%dMB  plugin=%s%n",
                elapsedMs / 1000L, done, done / Math.max(elapsedMs / 1000.0d, 1.0d),
                success.get(), statusError.get(), exceptions.get(), heapMb, fetchPluginCounters());
        }
    }

    /** 读取插件 /inner/sw/metrics 的关键计数（短超时；失败不影响压测）。 */
    private String fetchPluginCounters() {
        try (CloseableHttpClient c = HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(2000).setSocketTimeout(2000).build())
                .build()) {
            final HttpGet get = new HttpGet(METRICS_URL);
            try (CloseableHttpResponse resp = c.execute(get)) {
                final String body = EntityUtils.toString(resp.getEntity());
                return "enabled=" + num(body, "\"enabled\"\\s*:\\s*(true|false)")
                    + " storage=" + num(body, "\"storageEnabled\"\\s*:\\s*(true|false)")
                    + " endpointOverflow=" + num(body, "\"endpointOverflow\"\\s*:\\s*(\\d+)")
                    + " sampleOverflow=" + num(body, "\"sampleOverflow\"\\s*:\\s*(\\d+)")
                    + " lateDropped=" + num(body, "\"lateDropped\"\\s*:\\s*(\\d+)")
                    + " persistErrors=" + num(body, "\"persistErrors\"\\s*:\\s*(\\d+)")
                    + " aggregateErrors=" + num(body, "\"aggregateErrors\"\\s*:\\s*(\\d+)")
                    + " rowsUpserted=" + num(body, "\"rowsUpserted\"\\s*:\\s*(\\d+)");
            }
        } catch (Exception e) {
            return "(metrics 读口不可达: " + e.getClass().getSimpleName() + ")";
        }
    }

    private static String num(final String body, final String regex) {
        final Matcher m = Pattern.compile(regex).matcher(body);
        return m.find() ? m.group(1) : "?";
    }

    private static void latch(final Map<Integer, AtomicLong> statusCounts, final int statusCode) {
        AtomicLong counter = statusCounts.get(statusCode);
        if (counter == null) {
            synchronized (statusCounts) {
                counter = statusCounts.get(statusCode);
                if (counter == null) {
                    counter = new AtomicLong(0);
                    statusCounts.put(statusCode, counter);
                }
            }
        }
        counter.incrementAndGet();
    }

    private List<Long> latencyMillis() {
        final List<Long> all = new ArrayList<Long>();
        synchronized (workers) {
            for (Worker w : workers) {
                all.addAll(w.sampleMillis());
            }
        }
        Collections.sort(all);
        return all;
    }

    private void printReport(final long wallMs, final long success, final long statusError, final long exceptions,
            final Map<Integer, AtomicLong> statusCounts, final List<Long> latenciesMs) {
        final double seconds = Math.max(wallMs, 1L) / 1000.0d;
        System.out.println();
        System.out.println("---- 结果 ----");
        System.out.printf("总耗时        : %d ms%n", wallMs);
        System.out.printf("吞吐(RPS)     : %.1f%n", (success + statusError + exceptions) / seconds);
        System.out.printf("成功(2xx)     : %d%n", success);
        System.out.printf("非 2xx        : %d%n", statusError);
        System.out.printf("网络异常      : %d%n", exceptions);
        System.out.println("延迟(ms)      : avg=" + avg(latenciesMs)
            + " p50=" + percentile(latenciesMs, 50) + " p90=" + percentile(latenciesMs, 90)
            + " p95=" + percentile(latenciesMs, 95) + " p99=" + percentile(latenciesMs, 99)
            + " max=" + (latenciesMs.isEmpty() ? 0 : latenciesMs.get(latenciesMs.size() - 1))
            + " (样本 " + latenciesMs.size() + ")");
        final Map<Integer, AtomicLong> sorted = new TreeMap<Integer, AtomicLong>(statusCounts);
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, AtomicLong> e : sorted.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue().get()).append(' ');
        }
        System.out.println("状态码分布    : " + sb);
        System.out.println("插件计数      : " + fetchPluginCounters());
        System.out.println("----------------");
    }

    private static long avg(final List<Long> sorted) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        for (Long v : sorted) {
            sum += v;
        }
        return sum / sorted.size();
    }

    private static long percentile(final List<Long> sorted, final int p) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int rank = (int) Math.ceil(p / 100.0d * sorted.size());
        if (rank < 1) {
            rank = 1;
        }
        if (rank > sorted.size()) {
            rank = sorted.size();
        }
        return sorted.get(rank - 1);
    }

    /** 工作线程：请求数模式发够即退；时长/无限模式循环到 running=false。 */
    private final class Worker implements Runnable {
        private final CloseableHttpClient client;
        private final CountDownLatch gate;
        private final long[] samples = new long[SAMPLES_PER_THREAD];
        private int sampleCount = 0;
        private long seen = 0L;

        Worker(final CloseableHttpClient client, final CountDownLatch gate) {
            this.client = client;
            this.gate = gate;
        }

        @Override
        public void run() {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            while (running.get()) {
                if (!DURATION_MODE) {
                    final int i = index.getAndIncrement();
                    if (i >= NUM_REQUESTS) {
                        return;
                    }
                }
                final String path = PATHS[ThreadLocalRandom.current().nextInt(PATHS.length)];
                final String url = BASE_URL + randomize(path)
                    + (path.contains("?") ? "&" : "?") + "load=" + seq.incrementAndGet();
                final long reqStart = System.nanoTime();
                try {
                    final HttpGet httpGet = new HttpGet(url);
                    try (CloseableHttpResponse response = client.execute(httpGet)) {
                        final int statusCode = response.getStatusLine().getStatusCode();
                        EntityUtils.consumeQuietly(response.getEntity());
                        latch(statusCounts, statusCode);
                        if (statusCode >= 200 && statusCode < 300) {
                            success.incrementAndGet();
                        } else {
                            statusError.incrementAndGet();
                        }
                    }
                } catch (IOException e) {
                    exceptions.incrementAndGet();
                } finally {
                    recordLatency(System.nanoTime() - reqStart);
                }
            }
        }

        /** 蓄水池采样：内存有界。 */
        private void recordLatency(final long nanos) {
            final long ms = nanos / 1_000_000L;
            seen++;
            if (sampleCount < SAMPLES_PER_THREAD) {
                samples[sampleCount++] = ms;
            } else {
                final long pick = Math.floorMod(ThreadLocalRandom.current().nextLong(), seen);
                if (pick < SAMPLES_PER_THREAD) {
                    samples[(int) pick] = ms;
                }
            }
        }

        List<Long> sampleMillis() {
            final List<Long> out = new ArrayList<Long>(sampleCount);
            for (int i = 0; i < sampleCount; i++) {
                out.add(samples[i]);
            }
            return out;
        }
    }

    /**
     * 把路径里的 <code>{rand:min,max}</code> 换成区间内的随机整数，<b>每个请求各换一次</b>。
     *
     * <p>为什么需要：像 {@code /api/export/report} 这种端点，时长本身就是它要演示的东西
     * （默认 sleep 65s）。写死一个值只能压出一条固定耗时的线；写成 4 个变体又会让它在
     * 路径列表里占 4 席、把整体流量分布带偏。占位符让<b>一条路径</b>产出<b>连续的耗时分布</b>。
     *
     * <p>用法：<code>/api/export/report?ms={rand:1000,4000}</code>
     * （Spring 会把花括号绑成 int 失败 → 400，所以不替换的话压测会直接报非 2xx ——
     * 这也正好让"忘了替换"不可能悄悄通过。）
     */
    private static String randomize(final String path) {
        if (path == null || path.indexOf("{rand:") < 0) {
            return path;
        }
        final StringBuilder out = new StringBuilder(path.length() + 8);
        int i = 0;
        while (i < path.length()) {
            final int open = path.indexOf("{rand:", i);
            if (open < 0) {
                out.append(path, i, path.length());
                break;
            }
            final int close = path.indexOf('}', open);
            if (close < 0) {
                out.append(path, i, path.length());
                break;
            }
            out.append(path, i, open);
            final String[] range = path.substring(open + "{rand:".length(), close).split(",", -1);
            int min;
            int max;
            try {
                min = Integer.parseInt(range[0].trim());
                max = range.length > 1 ? Integer.parseInt(range[1].trim()) : min;
            } catch (final NumberFormatException e) {
                // 写错了就原样留着：让它 400，比"悄悄用个默认值"更容易发现
                out.append(path, open, close + 1);
                i = close + 1;
                continue;
            }
            if (max < min) {
                final int swap = min;
                min = max;
                max = swap;
            }
            out.append(min >= max ? min : min + ThreadLocalRandom.current().nextInt(max - min + 1));
            i = close + 1;
        }
        return out.toString();
    }

    /**
     * 按逗号切路径，但只在<b>该逗号真的分隔两条路径</b>时才切。
     *
     * <p><b>为什么需要</b>：路径集用逗号分隔，而逗号在 URL query 值里是合法字符 —— 朴素的
     * {@code split(",")} 会把一条路径静默拆成两条。症状不是报错，而是"压测少打了一半路径"
     * 或"凭空多出 400"，很难往切分上想。两类真实踩过的坑：
     * <ul>
     *   <li>{@code {rand:min,max}} 占位符（{@link #randomize}）：{@code {rand:1000,4000}}
     *       被切成 {@code {rand:1000} 和 {@code 4000}}，前者找不到闭合 {@code } 原样发出 → 400。
     *       这条真在 stress-slow.ps1 里踩到过（e495abf 引入）。</li>
     *   <li>query 参数值里带逗号（{@code ?ids=1,2,3}）。</li>
     * </ul>
     *
     * <p><b>判据</b>：路径集里每条路径都以 {@code /} 开头，而 query 值里的逗号后面跟的是
     * 数字或字母。所以 <b>depth==0 且后继非空白字符是 {@code /} 的逗号才算分隔符</b>。
     * 花括号 depth 是兜底 —— 万一将来出现"值里逗号紧跟斜杠"（{@code ?tags=a,/b}），depth 至少
     * 能护住占位符那类区间。
     *
     * <p><b>已知局限</b>（不追求"根治"，取舍见下）：判据是启发式的，所以
     * {@code ?tags=a,/b} 这种"值里的逗号后面紧跟斜杠"仍会被切开。真正的根治是让路径集
     * 不再与 URL 语法共用逗号（换分隔符），但分隔符要穿过 PowerShell + cmd.exe 两道手
     * （5a4d951 刚在 {@code &} 上踩过），代价高于收益 —— 等真有这种端点再说。
     *
     * <p><b>未闭合的 {@code &#123;</b> 到串尾：depth 永远回不到 0，剩余逗号全保留 —— 与
     * {@link #randomize} 对未闭合占位符"原样保留"的取向一致，让笔误一路走到 400，而不是被这里悄悄修复。
     */
    static String[] splitPaths(final String spec) {
        final List<String> out = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < spec.length(); i++) {
            final char c = spec.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (c == ',' && depth == 0 && isSeparator(spec, i)) {
                final String piece = spec.substring(start, i).trim();
                if (!piece.isEmpty()) {
                    out.add(piece);
                }
                start = i + 1;
            }
        }
        final String tail = spec.substring(start).trim();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out.toArray(new String[out.size()]);
    }

    /**
     * 这个 depth==0 的逗号是不是分隔符。
     *
     * <p>判据是"后继非空白字符是 {@code /}"（路径集里每条路径都以 {@code /} 开头，而 query
     * 值里的逗号后面跟的是数字或字母）。但不能只看这一条 —— 那样 {@code "/a,,/b"} 里第一个逗号
     * 的后继是另一个逗号，不满足判据、不切，于是片段变成 {@code "/a,"}（把逗号吃进路径里发出去）。
     * 所以还要放过"空片段"：本次逗号到下一个逗号（或串尾）之间只有空白时，它分隔的是空项，
     * 按"丢弃空片段"处理。
     */
    private static boolean isSeparator(final String spec, final int comma) {
        int i = comma + 1;
        boolean blankSoFar = true;
        for (; i < spec.length(); i++) {
            final char c = spec.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            // 空片段：本逗号与下一个分隔符之间没有内容，丢弃即可（否则逗号会被吃进路径）
            if (blankSoFar && c == ',') {
                return true;
            }
            return c == '/';
        }
        // 串尾：末尾逗号（空片段），丢弃
        return blankSoFar;
    }

    private static String stripTrailingSlash(final String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String prop(final String key, final String defaultValue) {
        final String value = System.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        // 剥掉一对外层双引号：那是 shell 的转义产物，不是数据本身。
        // 路径里常带 `&`（如 ?value=v&phase=P），而 Windows 上 mvn 是 mvn.cmd，
        // PowerShell 必须经 cmd.exe 调它，cmd 会在 `&` 处截断参数 —— 所以调用方
        // 只能用内嵌引号把整串包住，那对引号就会原样到这里，由本方法剥掉。
        String out = value.trim();
        if (out.length() >= 2 && out.startsWith("\"") && out.endsWith("\"")) {
            out = out.substring(1, out.length() - 1);
        }
        return out;
    }
}
