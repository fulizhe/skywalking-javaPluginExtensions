/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openskywalking.demo.controller;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import cn.hutool.http.HttpRequest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import redis.clients.jedis.Jedis;

/**
 * 依赖拓扑演示的造数端点:给 Cache / Database / MQ 三层与真实外呼各留一个入口,
 * 让依赖拓扑页({@code /dashboards/topology.html})在演示环境里就有分层可看。
 *
 * <h3>三条与拓扑页直接相关的口径</h3>
 * <ol>
 *   <li><b>中间件不在场也照调</b>——连不上照样产出依赖边(红边)。这样本机没有中间件
 *       也能验证"组件识别 + 错误率着色",而不是只看到"没有依赖"。</li>
 *   <li><b>异常一律吞掉</b>并写进响应体的 {@code error} 字段:Entry span 不标错、
 *       出口 span 标错,拓扑图上表现为"本服务的边红、端点不红"——这正是该页的边级
 *       错误口径;同时避免演示流量刷出 Trace 告警。</li>
 *   <li><b>每条出网调用都显式设超时</b>(见各类常量)。默认超时多为无限或几十秒,
 *       造数端点会被 16 线程压测打、也被页面 5s 轮询读,挂住即串行等待 + 半截数据。</li>
 * </ol>
 *
 * <p>刻意不用 {@code spring-boot-starter-data-redis} / {@code spring-kafka}:
 * 自动配置会在启动时就连中间件、且超时口径受 Boot 版本影响。手工建客户端让
 * "连不上也能返回"成为默认行为,超时值也只在这一处可查。
 *
 * @see <a href="https://github.com/lqzkcx3/skywalking-javaPluginExtensions">repo</a>
 */
@RestController
@RequestMapping("/api/deps-demo")
public class DepsDemoController {

    /** Redis 连接 + 命令超时(同一值):Jedis 的 timeout 参数同时覆盖两者。 */
    private static final int REDIS_TIMEOUT_MS = 1000;
    /** MySQL 超时写在 JDBC URL 上(见 application.yml 的 deps.mysql.url),这里只做兜底追加。 */
    private static final String MYSQL_FALLBACK_TIMEOUT = "?connectTimeout=2000&socketTimeout=3000";
    /** Kafka 三个超时:单次请求 / 交付总时长 / 阻塞上限(含元数据拉取)。 */
    private static final int KAFKA_REQUEST_TIMEOUT_MS = 3000;
    private static final int KAFKA_DELIVERY_TIMEOUT_MS = 5000;
    private static final int KAFKA_MAX_BLOCK_MS = 3000;
    /** 外呼超时(连接 + 读)。 */
    private static final int HTTP_TIMEOUT_MS = 3000;
    /** {@code sleepMs} 上限:不得大于 socketTimeout,否则是自己把自己拖死。 */
    private static final int MAX_SLEEP_MS = 3000;
    /** consume 的 poll 超时:短超时,拿不到消息就当"暂无数据"返回。 */
    private static final int KAFKA_POLL_TIMEOUT_MS = 300;
    /** Kafka 建 socket 的上限:broker 不可达时靠它把等待截断(默认 10s 会拖垮压测)。 */
    private static final int KAFKA_SOCKET_SETUP_MS = 1000;
    /** Kafka 整体 API 超时:元数据拉取等内部等待的兜底上限。 */
    private static final int KAFKA_API_TIMEOUT_MS = 1500;
    /** MySQL 驱动类:fat jar 下需显式加载,见 {@link #loadMysqlDriver()}。 */
    private static final String MYSQL_DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";

    private static final String DEMO_KEY = "sw:demo";
    private static final String DEMO_VALUE = "v1";

    @Value("${deps.redis.host:localhost}")
    private String redisHost;
    @Value("${deps.redis.port:6379}")
    private int redisPort;
    @Value("${deps.mysql.url:jdbc:mysql://localhost:3306/demo}")
    private String mysqlUrl;
    @Value("${deps.mysql.username:demo}")
    private String mysqlUser;
    @Value("${deps.mysql.password:demo}")
    private String mysqlPassword;
    @Value("${deps.kafka.bootstrap:localhost:9092}")
    private String kafkaBootstrap;
    @Value("${deps.kafka.topic:sw-demo}")
    private String kafkaTopic;

    /** 惰性建一次:KafkaProducer 自带后台线程与元数据缓存,每次请求新建太慢。 */
    private volatile KafkaProducer<String, String> producer;
    /** topic 只在本进程内确保一次:broker 不可达时反复试会把造数拖成十几秒。 */
    private volatile boolean topicEnsured;

    /**
     * Redis 造数:{@code op=get|set|del}。三种 op 在明细档会落成三个节点
     * (依赖节点身份 = 组件 + 出口操作名)。
     */
    @GetMapping("/redis")
    public Object redis(@RequestParam(value = "op", defaultValue = "get") String op) {
        String target = redisHost + ":" + redisPort + "/" + op;
        Map<String, Object> out = newResult(target);
        long begin = start();
        Jedis jedis = null;
        try {
            jedis = new Jedis(redisHost, redisPort, REDIS_TIMEOUT_MS);
            if ("set".equals(op)) {
                out.put("detail", jedis.set(DEMO_KEY, DEMO_VALUE));
            } else if ("del".equals(op)) {
                out.put("detail", String.valueOf(jedis.del(DEMO_KEY)));
            } else if ("get".equals(op)) {
                out.put("detail", jedis.get(DEMO_KEY));
            } else {
                out.put("ok", false);
                out.put("error", "不支持的 op:" + op + "(支持 get|set|del)");
            }
        } catch (Exception e) {
            markFailed(out, e);
        } finally {
            closeQuietly(jedis);
        }
        return stamp(out, begin);
    }

    /**
     * MySQL 造数:默认 {@code SELECT 1};{@code sleepMs>0} 走 {@code SLEEP(?)} 造慢边
     * (用来看分位与四档着色,否则一片 0ms 看不出差别)。不建表、不依赖 schema。
     */
    @GetMapping("/mysql")
    public Object mysql(@RequestParam(value = "sleepMs", defaultValue = "0") int sleepMs) {
        int sleep = Math.max(0, Math.min(sleepMs, MAX_SLEEP_MS));
        String sql = sleep > 0 ? "SELECT SLEEP(" + sleep + ")" : "SELECT 1";
        Map<String, Object> out = newResult(mysqlUrl + " [" + sql + "]");
        long begin = start();
        loadMysqlDriver();
        try (Connection conn = DriverManager.getConnection(withFallbackTimeout(mysqlUrl),
                mysqlUser, mysqlPassword);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            out.put("detail", rs.next() ? String.valueOf(rs.getObject(1)) : "(无结果)");
        } catch (Exception e) {
            markFailed(out, e);
        }
        return stamp(out, begin);
    }

    /**
     * 显式加载 MySQL 驱动。
     * <p>
     * 打包形态是 Spring Boot fat jar,驱动以嵌套 jar 存在;实测
     * {@code DriverManager} 在这种形态下**没有**通过 {@code META-INF/services/java.sql.Driver}
     * 自动注册它,直接 {@code getConnection} 会抛 "No suitable driver found"。
     * 显式 {@code Class.forName} 是确定性解法,也不碰 {@code spring.datasource}
     * (那会把 MySQL 顶掉 H2 演示库)。
     */
    private void loadMysqlDriver() {
        try {
            Class.forName(MYSQL_DRIVER_CLASS);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("classpath 缺少 MySQL 驱动:" + MYSQL_DRIVER_CLASS, e);
        }
    }

    /**
     * Kafka 造数:{@code op=produce|consume}。produce 发一条带时间戳的值,
     * consume poll 一次并回显最早那一条(拿不到不报错,当作"暂无数据")。
     */
    @GetMapping("/kafka")
    public Object kafka(@RequestParam(value = "op", defaultValue = "produce") String op) {
        Map<String, Object> out = newResult(kafkaBootstrap + "/" + kafkaTopic + "/" + op);
        long begin = start();
        try {
            ensureTopic();
            if ("consume".equals(op)) {
                out.put("detail", consumeOnce());
            } else if ("produce".equals(op)) {
                out.put("detail", produceOnce());
            } else {
                out.put("ok", false);
                out.put("error", "不支持的 op:" + op + "(支持 produce|consume)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markFailed(out, e);
        } catch (Exception e) {
            markFailed(out, e);
        }
        return stamp(out, begin);
    }

    /**
     * <b>一次打穿全部四层依赖</b>（Cache / Database / MQ / 外呼）——演示与截图用的单一入口。
     *
     * <p>为什么单独有这个端点,而不是把三层调用塞进 {@code /fullSample}:
     * {@code /fullSample} 是**迁移自旧 sb-skywalking demo 工程**的初学者样例(README 有明写),
     * 它的价值在于"与旧工程一致";把监控专用的造数端点混进去会让它既不像样例、也不再一致。
     * 于是职责分开:{@code /fullSample} 演示注解/链路,本端点演示**依赖面**。
     *
     * <p><b>耗时叠加</b>:四层顺序各调一次,中间件未起时约 8s(1+2+3+0.3+2),
     * 中间件都在时约 1s。这是**有界**的(每层各自有超时),不是挂住。
     *
     * @param sleepMs 传给 MySQL 那层,用来造一条明显慢的边
     * @param site    传给外呼那层(白名单内)
     */
    @GetMapping("/all")
    public Object all(@RequestParam(value = "sleepMs", defaultValue = "0") int sleepMs,
            @RequestParam(value = "site", defaultValue = "httpbin") String site) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("note", "四层顺序各调一次;耗时叠加但每层有超时,不会挂住");
        long begin = start();
        out.put("cache", redis("set"));
        out.put("database", mysql(sleepMs));
        out.put("mq", kafka("produce"));
        out.put("http", http(site));
        int ok = 0;
        for (String layer : new String[] {"cache", "database", "mq", "http"}) {
            Object part = out.get(layer);
            if (part instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) part).get("ok"))) {
                ok++;
            }
        }
        out.put("layersTotal", 4);
        out.put("layersOk", ok);
        out.put("latencyMs", (System.nanoTime() - begin) / 1_000_000L);
        return out;
    }

    /**
     * 真实站点外呼:{@code site=httpbin|baidu|google}。
     * **白名单枚举**——演示端点不接受任意 URL(否则就是个 SSRF 口子)。
     * 三个站点同属 HTTP 组件,拓扑图上仍是同一个节点(节点只到组件类型)。
     */
    @GetMapping("/http")
    public Object http(@RequestParam("site") String site) {
        Map<String, String> sites = sites();
        String url = sites.get(site);
        Map<String, Object> out = newResult(url == null ? "拒绝的 site:" + site : url);
        long begin = start();
        if (url == null) {
            out.put("ok", false);
            out.put("error", "site 只支持白名单:" + String.join("、", sites.keySet()));
            return stamp(out, begin);
        }
        try {
            String body = HttpRequest.get(url).timeout(HTTP_TIMEOUT_MS).execute().body();
            out.put("detail", body == null ? "(空响应体)" : body.substring(0, Math.min(120, body.length())));
        } catch (Exception e) {
            markFailed(out, e);
        }
        return stamp(out, begin);
    }

    // ---------------------------------------------------------------- 内部

    /** 外呼白名单:配置缺省时退回内联表,避免 yaml 写漏导致端点全废。 */
    private Map<String, String> sites() {
        Map<String, String> sites = new LinkedHashMap<>();
        sites.put("httpbin", "https://httpbin.org/get");
        sites.put("baidu", "https://www.baidu.com/");
        sites.put("google", "https://www.google.com/generate_204");
        return sites;
    }

    private Map<String, Object> newResult(final String target) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE);
        out.put("target", target);
        out.put("latencyMs", null);
        out.put("detail", null);
        out.put("error", null);
        return out;
    }

    private long start() {
        return System.nanoTime();
    }

    private void markFailed(final Map<String, Object> out, final Exception e) {
        out.put("ok", Boolean.FALSE);
        out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private Map<String, Object> stamp(final Map<String, Object> out, final long begin) {
        out.put("latencyMs", (System.nanoTime() - begin) / 1_000_000L);
        return out;
    }

    /** MySQL URL 没带超时参数时补上默认值——超时必须是显式的,不能依赖驱动默认。 */
    private String withFallbackTimeout(final String url) {
        if (url != null && url.contains("connectTimeout=")) {
            return url;
        }
        String base = url == null ? "" : url;
        return base + (base.contains("?") ? "&" : MYSQL_FALLBACK_TIMEOUT.substring(1));
    }

    private String produceOnce() throws Exception {
        KafkaProducer<String, String> p = producer();
        String value = "v-" + System.currentTimeMillis();
        p.send(new ProducerRecord<>(kafkaTopic, DEMO_KEY, value)).get(KAFKA_DELIVERY_TIMEOUT_MS,
                TimeUnit.MILLISECONDS);
        return value;
    }

    private String consumeOnce() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "sw-demo-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(KAFKA_REQUEST_TIMEOUT_MS));
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, String.valueOf(KAFKA_API_TIMEOUT_MS));
        props.put(ConsumerConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG,
                String.valueOf(KAFKA_SOCKET_SETUP_MS));
        // 反序列化器必填:缺了会在**触网前**就抛 ConfigException,那既造不出依赖边、
        // 也测不到超时 —— 出口 span 压根不会产生。
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // **assign 而不是 subscribe**,且**刻意不 close** —— 两个都是被 broker 不可达实测逼出来的:
        //   subscribe 会引入手入组/再均衡协调,kafka-clients 2.8.2 的 consumer 在 broker 不可达时
        //   close() 无界等待(实测 > 40s 仍未返回),而这个版本**没有** api.close.request.timeout.ms
        //   可压 —— 试过,加这个字面key 完全无效,已删。
        //   造数端点不值得为它养一个后台线程,consumer 直接被丢弃由 GC 回收。
        // 位点重置交给 auto.offset.reset=earliest,**不显式 seek**:
        //   seekToBeginning 要先取元数据 + offset,broker 不可达时按 request.timeout.ms 阻塞,
        //   实测单这一句就把 consume 从 ~2s 拖到 8s;重置挪进 poll() 内部则受 poll 时长约束。
        TopicPartition partition = new TopicPartition(kafkaTopic, 0);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.assign(Collections.singletonList(partition));
        ConsumerRecords<String, String> records = consumer.poll(
                java.time.Duration.ofMillis(KAFKA_POLL_TIMEOUT_MS));
        for (ConsumerRecord<String, String> record : records) {
            return record.value();
        }
        return "(暂无数据)";
    }

    private synchronized KafkaProducer<String, String> producer() {
        if (producer == null) {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap);
            props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(KAFKA_REQUEST_TIMEOUT_MS));
            props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, String.valueOf(KAFKA_DELIVERY_TIMEOUT_MS));
            props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, String.valueOf(KAFKA_MAX_BLOCK_MS));
            props.put(ProducerConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG,
                    String.valueOf(KAFKA_SOCKET_SETUP_MS));
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            producer = new KafkaProducer<>(props);
        }
        return producer;
    }

    /**
     * 首次造数前确保 topic 存在。
     * <p>
     * **每次调用都试**是错的:broker 不可达时 AdminClient 会耗掉整个
     * {@code default.api.timeout.ms},把"造一条红边"拖成十几秒 —— 压测与页面轮询都受不了。
     * 故只在本进程内试一次,失败即记住,之后直接走真正的 produce/consume,
     * 让出口 span 如实记录成功或失败。
     */
    private void ensureTopic() {
        if (topicEnsured) {
            return;
        }
        topicEnsured = true;
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(KAFKA_REQUEST_TIMEOUT_MS));
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, String.valueOf(KAFKA_API_TIMEOUT_MS));
        try (AdminClient admin = AdminClient.create(props)) {
            admin.createTopics(Collections.singletonList(new NewTopic(kafkaTopic, 1, (short) 1)))
                    .all()
                    .get(KAFKA_API_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // topic 已存在 / broker 不支持 / 连不上:都不该让造数端点整体失败——
            // 真正的成败由下面真正的 produce/consume 用出口 span 如实记录。
        }
    }

    private void closeQuietly(final Jedis jedis) {
        if (jedis != null) {
            try {
                jedis.close();
            } catch (Exception ignored) {
                // 关闭失败不影响造数结果
            }
        }
    }
}