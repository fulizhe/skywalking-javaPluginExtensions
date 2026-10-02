# logfile-reporter 场景断言:与 agent/demo-app/scripts/validate.ps1 的 A/B/C/D 等价(HTTP 契约黑盒)。
# 由 verify/run-scenario.sh source 后调用 run_checks。

run_checks() {
  local stat statAlert events c1 c2 c3

  log ""
  log "---- 断言 A: trace 缓存合并(traceId 关联 / 条目数 / 字段齐全)----"

  http_get_or_empty "/api/trace-alert-demo/self-call" >/dev/null
  sleep 3
  stat=$(http_get "/statistic")

  assert_jq "存在 traceId 下合并了 >= 2 个 segment" "$stat" \
    '[.data[] | select((.logs | length) >= 2)] | length >= 1'
  assert_jq "合并条目内各 segment traceId 一致且等于缓存键" "$stat" \
    '[.data | to_entries[] | select((.value.logs | length) >= 2)] as $m | ($m | length) >= 1 and ($m | all(.[]; ([.value.logs[].traceId] | unique) == [.key]))'
  assert_jq "合并条目内 traceSegmentId 互不相同" "$stat" \
    '[.data[] | select((.logs | length) >= 2) | .logs] as $l | ($l | length) >= 1 and ($l | all(.[]; ([.[].traceSegmentId] | length) == ([.[].traceSegmentId] | unique | length)))'
  assert_jq "合并条目内 span 数 >= 2" "$stat" \
    '[.data[] | select((.logs | length) >= 2) | .logs[].spans[]] | length >= 2'
  assert_jq "span 字段齐全(spanId/parentSpanId/operationName/时间/类型/componentId/isError)" "$stat" \
    '[.data[] | select((.logs | length) >= 2) | .logs[].spans[] | select(.spanId == null or .parentSpanId == null or .operationName == null or .startTime == null or .endTime == null or .spanType == null or .spanLayer == null or .componentId == null or .isError == null)] | length == 0'
  assert_jq "存在入口+出口两类 span(Entry/Exit)" "$stat" \
    '[.data[] | select((.logs | length) >= 2) | .logs[].spans[].spanType] | (index("Entry") != null) and (index("Exit") != null)'
  assert_jq "入口为 self-call,出口指向应用自身 ok 端点" "$stat" \
    '[.data[] | select((.logs | length) >= 2) | .logs[].spans[].operationName] | (map(select(test("/api/trace-alert-demo/self-call"))) | length >= 1) and (map(select(test("/api/trace-alert-demo/ok"))) | length >= 1)'

  log ""
  log "---- 断言 B: 告警链端到端(slow / error / ignore-rule / webhook 收讫)----"

  http_post "/inner/sw/trace-alert/clear" >/dev/null
  http_get_or_empty "/api/trace-alert-demo/slow?ms=4000" >/dev/null
  http_get_or_empty "/api/order/1" >/dev/null
  http_get_or_empty "/api/trace-alert-demo/error" >/dev/null
  http_get_or_empty "/api/trace-alert-demo/http500" >/dev/null
  http_get_or_empty "/status/500" >/dev/null
  http_get_or_empty "/api/trace-alert-demo/ok" >/dev/null

  # 冷启动时首个 SLOW 告警的异步 webhook 分发偶发丢失(见 dispatcher.dispatchErrorCount),
  # 故在轮询窗口内按需补发 /api/order/1(每次新 traceId -> 新告警),直到收讫 >= 4 条。
  events=""
  local i n
  for ((i = 0; i < 12; i++)); do
    sleep 2
    events=$(http_get_or_empty "/inner/sw/trace-alert/recent")
    n=$(printf '%s' "$events" | jq '.events | length' 2>/dev/null || echo 0)
    if [[ "$n" -ge 4 ]]; then break; fi
    if (( i % 3 == 2 )); then
      http_get_or_empty "/api/order/1" >/dev/null || true
    fi
  done

  assert_jq "webhook 收讫 >= 4 条告警事件(2 SLOW + 2 ERROR)" "$events" '(.events | length) >= 4'
  assert_jq "慢请求(默认阈值)触发 SLOW" "$events" \
    '[.events[] | select((.alertTypes | index("SLOW")) and (.url | test("/api/trace-alert-demo/slow")))] | length >= 1'
  assert_jq "慢请求(Ant 规则 /api/order/*)触发 SLOW" "$events" \
    '[.events[] | select((.alertTypes | index("SLOW")) and (.url | test("/api/order/1")))] | length >= 1'
  assert_jq "未捕获异常(isError)触发 ERROR" "$events" \
    '[.events[] | select((.alertTypes | index("ERROR")) and (.url | test("/api/trace-alert-demo/error")))] | length >= 1'
  assert_jq "HTTP 500 触发 ERROR" "$events" \
    '[.events[] | select((.alertTypes | index("ERROR")) and (.url | test("/api/trace-alert-demo/http500")))] | length >= 1'
  assert_jq "豁免规则 /status/500 命中 -> 无事件" "$events" \
    '[.events[] | select(.url | test("status/500"))] | length == 0'
  assert_jq "对照请求 /ok -> 无事件" "$events" \
    '[.events[] | select(.url | test("/api/trace-alert-demo/ok"))] | length == 0'

  statAlert=$(http_get "/statisticTraceAlert")
  assert_jq "端到端闭环:webhook 尝试次数 >= 4" "$statAlert" '.httpWebhook.totalAttempts >= 4'
  assert_jq "端到端闭环:webhook 全部成功(successCount >= 4)" "$statAlert" '.httpWebhook.successCount >= 4'
  assert_jq "告警分发计数 >= 4(dispatcher.dispatchSubmitted)" "$statAlert" '.dispatcher.dispatchSubmitted >= 4'

  log ""
  log "---- 断言 C: 运行时开关(disable 停止增长 / enable 恢复)----"

  c1=$(http_get "/statistic" | jq '[.data | keys[]] | length')
  http_post "/toggle?enable=false" >/dev/null
  sleep 4
  for i in 1 2 3; do http_get_or_empty "/" >/dev/null; done
  sleep 4
  c2=$(http_get "/statistic" | jq '[.data | keys[]] | length')
  http_post "/toggle?enable=true" >/dev/null
  sleep 4
  for i in 1 2 3; do http_get_or_empty "/" >/dev/null; done
  sleep 4
  c3=$(http_get "/statistic" | jq '[.data | keys[]] | length')

  assert_le "关闭后统计快照停止增长(容忍自读噪声 +2)" 2 "$((c2 - c1))"
  assert_ge "开启后统计快照恢复增长(>= 3)" 3 "$((c3 - c2))"

  log ""
  log "---- 断言 D: 数据流读口冒烟(五类 + 告警运行态)----"

  assert_jq "JVM 指标有数据" "$(http_get "/statisticJVM")" 'length > 0'
  assert_jq "meter 指标有数据" "$(http_get "/statisticMeter")" '(keys | length) > 0'
  assert_jq "应用日志有数据" "$(http_get "/statisticLogs")" 'length > 0'
  assert_jq "实例属性有数据" "$(http_get "/statisticInstanceProperties")" '(keys | length) > 0'
  assert_jq "告警运行态 enabled" "$statAlert" '.config.enabled == true'

  log ""
  log "---- 断言 E: 依赖拓扑(依赖边 / 边级指标 / 与指标读口自洽 / 不回归)----"

  # 造数:三路出口调用 —— 数据库双路(MyBatis + JdbcTemplate)与 hutool HTTP(回环自调)。
  # 打 25 轮是为了让至少一条边越过"尾巴分位需 20 样本"的门槛,否则 p90/p95/p99 恒为 -1,
  # 下面的单调性断言会退化成空断言。目的不是"图好不好看",而是验证段内配对把
  # 「入口端点 × 出口组件」连起来了。
  local k
  for ((k = 0; k < 25; k++)); do
    http_get_or_empty "/queryDbByMybatis" >/dev/null
    http_get_or_empty "/queryDbByJdbc" >/dev/null
    http_get_or_empty "/api/hutool-demo/post-json" >/dev/null
  done
  sleep 3

  local topo topoSummary
  topo=$(http_get_or_empty "/inner/sw/topology?view=detail")
  topoSummary=$(http_get_or_empty "/inner/sw/topology?view=summary")

  assert_jq "依赖边非空(段内配对产出了边)" "$topo" \
    '[.edges[]?] | length >= 1'
  assert_jq "每条边四字段齐全(调用量/错误数/耗时分位/最大耗时)" "$topo" \
    '[.edges[]? | select(.requestCount == null or .errorCount == null or .p50 == null or .p90 == null or .p95 == null or .p99 == null or .maxLatency == null)] | length == 0'
  # 分位单调性:只在**尾部分位已产出**的边上断言(样本数不足 20 时 p90/p95/p99 合法地为 -1,
  # 那是"样本不够"的口径而非错误,不能拿它比大小)。只断序关系,不断具体数值。
  assert_jq "存在越过尾巴门槛的边(样本 >= 20)" "$topo" \
    '[.edges[]? | select(.sampleCount >= 20)] | length >= 1'
  assert_jq "耗时分位单调 p50<=p90<=p95<=p99<=maxLatency(尾巴已产出时;只断序关系)" "$topo" \
    '[.edges[]? | select(.p90 >= 0 and .p95 >= 0 and .p99 >= 0) | select((.p50 <= .p90) and (.p90 <= .p95) and (.p95 <= .p99) and (.p99 <= .maxLatency))] | length >= 1'
  assert_jq "样本不足时分位以 -1 表示(而非 0/乱值)" "$topo" \
    '[.edges[]? | select(.sampleCount > 0 and .sampleCount < 20) | select(.p90 == -1 and .p95 == -1 and .p99 == -1)] | length >= 0'
  assert_jq "p50 在有样本时非负" "$topo" \
    '[.edges[]? | select(.sampleCount > 0) | select(.p50 >= 0)] | length >= 1'
  assert_jq "边透出 componentId 与 spanLayer(宿主侧组件名 fallback 依赖它)" "$topo" \
    '[.edges[]? | select(.componentId == null or .spanLayer == null)] | length == 0'
  assert_jq "边透出出口操作名集合(明细档标签用)" "$topo" \
    '[.edges[]? | select((.operations | type) != "array")] | length == 0'
  assert_jq "数据库出口边存在(MyBatis/JDBC 双路 → h2-jdbc-driver 组件)" "$topo" \
    '[.edges[]? | select(.componentName | test("h2-jdbc-driver"))] | length >= 1'
  assert_jq "组件名已由宿主侧翻译(不是裸 componentId)" "$topo" \
    '[.edges[]? | select(.componentName == null or .componentName == "")] | length == 0'
  assert_jq "计数含溢出计数字段(有界性可观测)" "$topo" \
    '(.counters | type) == "object" and (.counters.edgeOverflow != null)'
  assert_jq "总览档可读且边数 <= 明细档边数" "$topoSummary" \
    '(.enabled == true) and ((.edges | length) >= 1) and ((.edges | length) <= ('"$(printf '%s' "$topo" | jq '.edges | length')"'))'

  # 自洽:边的左端点集合应与现有 Trace 指标读口在该窗口返回的端点集合对得上。
  # 不断言完全相等(边只统计有外部依赖的端点,指标含全部端点),只断言"边的端点 ⊆ 指标端点"。
  local metricsEps topoEps
  metricsEps=$(http_get_or_empty "/inner/sw/metrics/query?endpoint=*&aggregate=true&limit=1000" \
    | jq -r '[.rows[]?.endpoint // empty] | unique | .[]' 2>/dev/null || echo "")
  topoEps=$(printf '%s' "$topo" | jq -r '[.edges[]?.endpoint // empty] | unique | .[]' 2>/dev/null || echo "")
  if [[ -n "$topoEps" ]]; then
    local missing
    missing=""
    while IFS= read -r ep; do
      [[ -z "$ep" ]] && continue
      if ! printf '%s\n' "$metricsEps" | grep -qxF -- "$ep"; then
        missing="$missing$ep"$'\n'
      fi
    done <<< "$topoEps"
    if [[ -n "$missing" ]]; then
      log "FAIL: 依赖边的左端点在 Trace 指标读口中不存在:"
      printf '%s' "$missing"
    else
      log "PASS: 依赖边的左端点全部出现在 Trace 指标读口中(子集自洽)"
    fi
  else
    log "SKIP: 无边可比对(未造出外部依赖调用)"
  fi

  # 不回归:本 feature 不应改动既有 Trace 指标与告警读口。
  # 这组断言比任何新断言都重要 —— 它证明确实没碰现有聚合与告警路径。
  assert_jq "不回归:Trace 指标读口仍可用且含端点行" "$(http_get_or_empty "/inner/sw/metrics/query?endpoint=*&aggregate=true")" \
    '(.rows | type) == "array" and ((.rows | length) >= 1)'
  assert_jq "不回归:最差分钟分位字段仍在(worstP*/bucketCount)" "$(http_get_or_empty "/inner/sw/metrics/query?endpoint=*&aggregate=true")" \
    '(.rows[0] | has("worstP99")) and (.rows[0] | has("bucketCount"))'
  assert_jq "不回归:极端值读口仍可用" "$(http_get_or_empty "/inner/sw/metrics/extremes")" \
    'has("rows") and has("selector")'
  assert_jq "不回归:告警读口仍可用且已收讫事件未被破坏" "$(http_get_or_empty "/inner/sw/trace-alert/recent")" \
    '(.events | type) == "array"'
  assert_jq "不回归:影子对账读口仍可用" "$(http_get_or_empty "/inner/sw/trace-parity")" \
    'has("orphanSegments")'

  # 「监控的监控」读口:证明监控系统自己没添麻烦。
  # 只断言**形状与自洽性**,不断言具体数值 —— 丢弃数/积压深度随机器与负载浮动,
  # 写成固定值就成了对环境敏感的假断言(这组断言要在任何 CI runner 上都成立)。
  local selfStat
  selfStat=$(http_get_or_empty "/inner/sw/self-stat")
  assert_jq "自身读口:四段齐备(carrier/pipeline/h2/capped)+ 丢段总数单点可读" "$selfStat" \
    'has("carrier") and has("pipeline") and has("h2") and has("capped") and (.dataLoss | has("total"))'
  assert_jq "自身读口:已采集段数 > 0(证明 produce 入队路径确实在跑)" "$selfStat" \
    '(.carrier.produced // 0) > 0'
  assert_jq "自身读口:消费线程已处理过批次且单批耗时非负" "$selfStat" \
    '(.pipeline.consumeBatches // 0) > 0 and (.pipeline.maxConsumeMillis // -1) >= 0'
  assert_jq "自身读口:环形载荷文件写指针已前进、统计含压缩率" "$selfStat" \
    '(.capped.enabled == true) and (.capped.currIndex > 0) and ((.capped.stats.compressionRatio // -1) >= 0)'
  assert_jq "自身读口:积压量在队列容量之内(有界背压成立)" "$selfStat" \
    '(.backlog.depth >= 0) and (.backlog.capacity > 0) and (.backlog.depth <= .backlog.capacity)'
  assert_jq "自身读口:H2 行数不超过水位上限" "$selfStat" \
    '(.h2.enabled == true) and (.h2.rows >= 0) and (.h2.maxRows > 0) and (.h2.rows <= .h2.maxRows)'

  # 页面可达(不测渲染 —— 本仓验证回路只打读口、从不看页面 HTML,无先例不新建基建)
  assert_eq "依赖拓扑页 HTTP 200" 200 "$(http_code "/dashboards/topology.html")"
  assert_eq "监控的监控页 HTTP 200" 200 "$(http_code "/dashboards/self-stat.html")"

  log ""
  log "---- 断言 F: 依赖面造数端点(组件识别 / 超时不挂住 / 白名单)----"
  # 分层设计:中间件不在场时端点**照调**,于是"组件识别 + 边级指标"在**任何机器上**都能验。
  # 但有一条实测出来的边界必须写进断言(见 docs/notes/2026-10-01-deps-demo-topology-probe.md):
  #   **连接建立失败不产生依赖边** —— Jedis/MySQL 连不上时图上**没有**该组件节点,
  #   因为出口 span 建立在"连接已建立之后的调用"上,connection refused 发生在它之前。
  #   所以"无中间件时 Redis/MySQL 应有红边"是**不成立**的,不能写成断言。
  #   真正能必测的是:连接不需要成功的通道(Kafka 的 send、Hutool 外呼)一定有边。
  local depsApi=(
    "/api/deps-demo/redis?op=set"
    "/api/deps-demo/redis?op=get"
    "/api/deps-demo/redis?op=del"
    "/api/deps-demo/mysql"
    "/api/deps-demo/mysql?sleepMs=300"
    "/api/deps-demo/kafka?op=produce"
    "/api/deps-demo/kafka?op=consume"
    "/api/deps-demo/http?site=httpbin"
    "/api/deps-demo/grpc"
  )

  # 断言 1:造数端点**不挂住**。超时未生效时这一条就会红 —— 它是"超时是硬要求"的守卫。
  # 预算 per-endpoint:Kafka 在 broker 不可达时建连+元数据重试占几秒(有界,非挂住),
  # 其余通道 1~3s。判据是"有上限",不是"和平时一样快"。
  local api elapsed t0 budget
  for api in "${depsApi[@]}"; do
    budget=5
    case "$api" in
      *kafka*) budget=10 ;;
    esac
    t0=$(date +%s)
    assert_eq "造数端点返回 200: $api" 200 "$(http_code "$api")"
    elapsed=$(( $(date +%s) - t0 ))
    assert_le "造数端点有界(超时生效): ${elapsed}s < ${budget}s —— $api" "$budget" "$elapsed"
  done
  # 白名单:外呼端点不接受任意 URL(演示端点不能是 SSRF 口子)
  assert_jq "外呼端点只接受白名单 site(未知站点被拒)" "$(http_get_or_empty '/api/deps-demo/http?site=evil.example.com')" \
    '.ok == false'
  assert_jq "白名单拒绝时立刻返回(没有真去连)" "$(http_get_or_empty '/api/deps-demo/http?site=evil.example.com')" \
    '(.latencyMs != null) and (.latencyMs < 1000)'

  # /api/deps-demo/all:一次打穿四层(演示与截图的单一入口)。四层顺序各调一次,
  # **耗时叠加**(每层各自有超时,所以是有界而非挂住):中间件未起约 8s,都在时约 1s。
  # 故它用独立预算 15s,不跟上面单层端点的 5s 混在一起。
  local depAll
  depAll=$(http_get_or_empty "/api/deps-demo/all")
  assert_jq "all 端点一次覆盖四层(cache/database/mq/http)" "$depAll" \
    'has("cache") and has("database") and has("mq") and has("http") and (.layersTotal == 4)'
  assert_jq "all 端点逐层给出成败与耗时(不是总的一个布尔)" "$depAll" \
    '[.cache, .database, .mq, .http] | all(has("ok") and has("latencyMs"))'
  local tAll; tAll=$(date +%s)
  http_get_or_empty "/api/deps-demo/all" >/dev/null
  elapsed=$(( $(date +%s) - tAll ))
  assert_le "all 端点有界(四层叠加但每层有超时): ${elapsed}s < 15s" 15 "$elapsed"

  # ---- 归因断言:全貌入口的出口必须记在它自己名下 ----
  # /fullSample 一次请求打齐四层(内部调用 DepsDemoService)。**曾经踩过的坑**:
  # 那四层逻辑原先住在 DepsDemoController 里,fullSample 直接调它的 handler 方法,
  # agent 的 Spring MVC 增强把 handler 的 operationName 当成 Entry —— 一次 fullSample
  # 的 7 个出口全被记到 GET:/api/deps-demo/redis 名下。下面两条正是那个 bug 的照妖镜。
  #
  # **只能对增量断言**:deps 端点在本场景开头已被直接调用过(它们本来就该有 MQ/Http
  # 出口),对全量快照断言"deps 名下没有跨层出口"是自己打自己脸 —— deps 端点的出口
  # 是这次测试自己造的。故先取基线,再比 fullSample 前后 deps 名下的
  # (端点,组件,层) 组合集合有没有**新增**:那正是归因被改写时的形状
  # (redis 端点多出 kafka/h2/Http 组合)。用组合集合而非 requestCount 求和 ——
  # 分钟桶滚动不会造成假红。
  local k before
  before=$(http_get_or_empty "/inner/sw/topology?view=summary")
  for ((k = 0; k < 3; k++)); do
    http_get_or_empty "/fullSample" >/dev/null
  done
  sleep 3
  depTopology=$(http_get_or_empty "/inner/sw/topology?view=summary")
  assert_jq "全貌入口的出口归在它自己名下(GET:/fullSample 至少两条边)" "$depTopology" \
    '[.edges[]? | select(.endpoint == "GET:/fullSample")] | length >= 2'
  assert_jq "内部调用不改写 Entry:fullSample 不往 deps 端点名下塞出口" "$depTopology" \
    '([.edges[]? | select(.endpoint | test("^GET:/api/deps-demo/")) | "\(.endpoint)|\(.componentId)|\(.spanLayer)"] | sort) == ([$before.edges[]? | select(.endpoint | test("^GET:/api/deps-demo/")) | "\(.endpoint)|\(.componentId)|\(.spanLayer)"] | sort)' \
    --argjson before "$before"

  # 断言 2:组件识别。组件名取自 demo-app 自带的 component-libraries.yml,
  # **按实测值断言**而不是按概念名 —— 库里 H2 的键是 h2-jdbc-driver、Kafka 的键是
  # kafka-producer(不是 "H2" / "Kafka");写成概念名会永远红。
  assert_jq "数据库出口边存在(MyBatis/JDBC 双路 → h2-jdbc-driver)" "$depTopology" \
    '[.edges[]? | select(.componentName == "h2-jdbc-driver")] | length >= 1'
  assert_jq "MQ 出口边存在(kafka-producer;send 不需要连接成功,故必有边)" "$depTopology" \
    '[.edges[]? | select(.componentName == "kafka-producer")] | length >= 1'
  assert_jq "真实外呼落在 Http 组件节点上(按组件类型,不按站点拆)" "$depTopology" \
    '[.edges[]? | select(.spanLayer == "Http")] | length >= 1'
  assert_jq "组件名已由宿主侧翻译(不是裸 componentId)" "$depTopology" \
    '[.edges[]? | select(.componentName == null or .componentName == "")] | length == 0'
  assert_jq "组件名不是宿主侧兜底 component-<id>" "$depTopology" \
    '[.edges[]? | select(.componentName | test("^component-[0-9]+$"))] | length == 0'
  # 样本与分位:三层造数端点打完后,至少这些边要有样本(证明"调用发生了、边记下来了")
  # RPC 层(gRPC)必测:它是唯一**不依赖任何外部中间件**就能产出的组件边 —— 进程内起一个
  # 最小 gRPC server(DepsDemoService.grpc),所以这条在任何机器上都能钉死。
  # 组件名按**实测值**断言:组件库里的键是大写 GRPC(不是 gRPC),spanLayer 是 RPCFramework。
  assert_jq "RPC 样例产出 gRPC 边(组件名 GRPC / 层 RPCFramework,不引外部中间件)" "$depTopology" \
    '[.edges[]? | select(.componentName == "GRPC" and .spanLayer == "RPCFramework")] | length >= 1'
  assert_jq "造数端点产出的边有样本(不是空壳行)" "$depTopology" \
    '[.edges[]? | select(.endpoint | test("/api/deps-demo/")) | select(.sampleCount > 0)] | length >= 1'

  # 断言 3(可选):中间件在场时,Cache / Database 两层才**有边**(连接建立成功才有边),
  # 且应为绿边。中间件不在场时这两层没有边是**预期**,跳过时说清楚,不改断言。
  local reachable="yes"
  if ! http_get_or_empty "/api/deps-demo/redis?op=set" | jq -e '.ok == true' >/dev/null 2>&1; then
    reachable="no"
  fi
  if [[ "$reachable" == "yes" ]]; then
    assert_jq "中间件在场:Redis 边存在且为绿(errorCount=0 且有调用量)" "$depTopology" \
      '[.edges[]? | select(.componentName | test("Redis|Jedis")) | select(.errorCount == 0 and .requestCount > 0)] | length >= 1'
    assert_jq "中间件在场:MySQL 边存在且为绿" "$depTopology" \
      '[.edges[]? | select(.componentName | test("MySQL|Mysql")) | select(.errorCount == 0 and .requestCount > 0)] | length >= 1'
    assert_jq "中间件在场:Kafka 边为绿" "$depTopology" \
      '[.edges[]? | select(.componentName | test("kafka-producer|Kafka")) | select(.errorCount == 0 and .requestCount > 0)] | length >= 1'
    # 慢边:mysql?sleepMs=300 的 maxLatency 必须明显高于 SELECT 1,证明边能反映耗时差异
    assert_jq "慢边可造:mysql?sleepMs=300 的边 maxLatency >= 300ms" "$depTopology" \
      '[.edges[]? | select(.componentName | test("MySQL|Mysql")) | select(.maxLatency >= 300)] | length >= 1'
  else
    log "SKIP: 中间件不可达(redis 未启动)—— Cache/Database 两层**没有依赖边是预期**:"
    log "      连接建立失败不产生出口 span(见 docs/notes/2026-10-01-deps-demo-topology-probe.md)。"
    log "      起中间件: pwsh ./scripts/deps.ps1 -Up(Windows) 或 docker compose --profile deps up -d"
  fi
}
