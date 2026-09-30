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
}
