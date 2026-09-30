# override-hutool 场景断言。由 verify/run-scenario.sh source 后调用 run_checks。
#
# 读取面:
#   /httpclient/collect/statistic  —— 共享采集开关读口(hutool 插件不自带读口,开关入口是
#                                     override-httpclient 插件增强的 SWHttpClientCollectUtils)
#   /statistic                     —— logfile-reporter 捕获的 span/tag(出口 span 的唯一读面)
#   /api/hutool-demo/*             —— hutool 出口触发端点;回显"业务自己读到的响应体"
#
# 断言组织(每段一个采集面):A 开关读口 → B query → C 表单体 → D 原始体 → E multipart 文件
#                        → F 响应体 + 业务不阻碍 → G 错误状态 → H 长度裁剪 → I 运行时开关
#
# 每段的参数值互不相同(marker 段内唯一),断言按值匹配 tag,避免命中上一阶段残留的 span。
# phase 参数进回环目标路径(/api/hutool-demo/echo/<phase>),使出口 span 可按 operationName 精确取。

# 组件 ID 128 = Hutool(见 OAP component-libraries.yml 的 Hutool: id: 128)
HUTOOL_COMPONENT_ID=128

# 按 operationName 精确取出口 span 数组
exit_spans() {
  printf '%s' "$1" | jq -c --arg op "$2" \
    '[.data[].logs[].spans[] | select(.spanType == "Exit" and .operationName == $op)]'
}

# tag_values <spans-json> <tag-key> —— 该批 span 上某 tag 的值数组(逐 span 取 tagList)
tag_values() {
  printf '%s' "$1" | jq -c --arg k "$2" \
    '[.[] | (.tagList // [])[]? | select(.["tag-key"] == $k) | (.["tag-value"] // "")]'
}

run_checks() {
  local stat statRaw r threshold spans
  local q_marker="query-e2e" f_marker="form-e2e" j_marker="json-e2e"
  local m_marker="multipart-e2e" off_marker="switch-off" on_marker="switch-on"
  # 超阈值体:长度远大于 httpParamsLengthThreshold(默认 1024),用于验证裁剪
  local clip_marker
  clip_marker=$(printf 'clip-e2e-%.0s' $(seq 1 300))

  log ""
  log "---- 断言 A: 共享采集开关读口(hutool 插件复用 override-httpclient 的开关入口)----"
  statRaw=$(http_get "/httpclient/collect/statistic")
  assert_jq "官方开关读口 officialCollectHttpParams=true" "$statRaw" '.raw.officialCollectHttpParams == true'
  assert_jq "override 开关读口 overrideCollectHttpParams=true" "$statRaw" '.raw.overrideCollectHttpParams == true'
  threshold=$(printf '%s' "$statRaw" | jq -r '.raw.httpParamsLengthThreshold // 0')
  assert_ge "裁剪阈值可读且为正数(plugin.http.http_params_length_threshold)" 1 "$threshold"

  log ""
  log "---- 断言 B: query string 采集(官方开关面)----"
  http_get_or_empty "/api/hutool-demo/get-query?marker=$q_marker" >/dev/null
  sleep 3
  stat=$(http_get "/statistic")
  spans=$(exit_spans "$stat" "/api/hutool-demo/echo/query")
  assert_jq "B1 存在 hutool 出口 span(Exit,componentId=$HUTOOL_COMPONENT_ID)" "$spans" \
    "length == 1 and .[0].componentId == $HUTOOL_COMPONENT_ID"
  assert_jq "B2 出口 span 的 parentSpanId = hutool 出口触发端点的入口 span(证明 span 产生自 hutool 调用)" "$stat" \
    '[.data[] | . as $t | ([$t.logs[].spans[] | select(.spanType == "Exit" and .operationName == "/api/hutool-demo/echo/query") | .parentSpanId]) as $exits
      | ([$t.logs[].spans[] | select(.spanType == "Entry" and .operationName == "GET:/api/hutool-demo/get-query") | .spanId]) as $entries
      | select(($exits | length) > 0 and ($entries | index($exits[0])) != null)] | length == 1'
  assert_jq "B3 http.request.params 采到 query=marker=$q_marker" "$(tag_values "$spans" http.request.params)" \
    '[.[] | select(contains($m))] | length == 1' --arg m "query=marker=$q_marker"

  log ""
  log "---- 断言 C: application/x-www-form-urlencoded 表单体采集(override 开关面)----"
  r=$(http_get "/api/hutool-demo/post-form?value=$f_marker")
  assert_jq "C0 业务侧调用成功且读到响应体(监控不阻碍业务)" "$r" '.businessBodyLength > 0'
  sleep 3
  stat=$(http_get "/statistic")
  spans=$(exit_spans "$stat" "/api/hutool-demo/echo/form")
  assert_jq "C1 http.request.params 采到 form=value=$f_marker" "$(tag_values "$spans" http.request.params)" \
    '[.[] | select(contains($m))] | length == 1' --arg m "form=value=$f_marker"
  assert_jq "C2 标准 tag http.params 同步写入同一值" "$(tag_values "$spans" http.params)" \
    '[.[] | select(contains($m))] | length == 1' --arg m "form=value=$f_marker"
  assert_jq "C3 http.method=POST" "$(tag_values "$spans" http.method)" '. == ["POST"]'
  assert_jq "C4 http.status_code=200" "$(tag_values "$spans" http.status_code)" '. == ["200"]'

  log ""
  log "---- 断言 D: body(...) 原始体采集 ----"
  http_get_or_empty "/api/hutool-demo/post-json?value=$j_marker" >/dev/null
  sleep 3
  stat=$(http_get "/statistic")
  spans=$(exit_spans "$stat" "/api/hutool-demo/echo/json")
  assert_jq "D1 http.request.params 采到 body={...$j_marker...}" "$(tag_values "$spans" http.request.params)" \
    '[.[] | select(contains($m))] | length == 1' --arg m "\"value\":\"$j_marker\""

  log ""
  log "---- 断言 E: multipart 文本字段 + 文件元数据采集 ----"
  r=$(http_get "/api/hutool-demo/post-multipart?value=$m_marker")
  assert_jq "E0 业务侧调用成功(目标 200)" "$r" '.httpStatus == 200'
  sleep 3
  stat=$(http_get "/statistic")
  spans=$(exit_spans "$stat" "/api/hutool-demo/echo/multipart")
  assert_jq "E1 文本字段进 http.request.params(value=$m_marker)" "$(tag_values "$spans" http.request.params)" \
    '[.[] | select(contains($m))] | length == 1' --arg m "value=$m_marker"
  assert_jq "E2 文件元数据进 http.request.files(filename)" "$(tag_values "$spans" http.request.files)" \
    '[.[] | select(contains($m))] | length == 1' --arg m "filename=hutool-verify-upload.txt"
  assert_jq "E3 文件条目含 size= 与 contentType=(只采元数据,不采文件内容)" "$(tag_values "$spans" http.request.files)" \
    '[.[] | select(test("size=[0-9]+") and test("contentType=[^,]+"))] | length == 1'

  log ""
  log "---- 断言 F: 响应体采集 + 业务侧仍能完整读到 body ----"
  r=$(http_get "/api/hutool-demo/post-form?value=$f_marker&phase=response")
  assert_jq "F1 业务侧读到的响应体含回显字段(插件采集未消耗流)" "$r" \
    '((.businessBody // "") | contains($m))' --arg m "value=$f_marker"
  sleep 3
  stat=$(http_get "/statistic")
  spans=$(exit_spans "$stat" "/api/hutool-demo/echo/response")
  assert_jq "F2 出口 span 的 http.response.body 采到回显内容" "$(tag_values "$spans" http.response.body)" \
    '[.[] | select(contains($m))] | length == 1' --arg m "value=$f_marker"
  assert_jq "F3 业务读到的 body 含完整回显 JSON(未被插件二次消费)" "$r" \
    '((.businessBody // "") | contains($m))' --arg m "\"echo\":\"value=$f_marker\""

  log ""
  log "---- 断言 G: 目标 500 -> 出口 span 置 isError ----"
  http_get_or_empty "/api/hutool-demo/error-call" >/dev/null
  sleep 3
  stat=$(http_get "/statistic")
  spans=$(exit_spans "$stat" "/api/hutool-demo/status/500")
  assert_jq "G1 存在该出口 span 且 isError=true" "$spans" 'length == 1 and .[0].isError == true'
  assert_jq "G2 http.status_code=500" "$(tag_values "$spans" http.status_code)" '. == ["500"]'

  log ""
  log "---- 断言 H: 超阈值请求体按阈值裁剪(不无界膨胀 tag)----"
  http_get_or_empty "/api/hutool-demo/post-json?value=$clip_marker&phase=clip" >/dev/null
  sleep 3
  stat=$(http_get "/statistic")
  spans=$(exit_spans "$stat" "/api/hutool-demo/echo/clip")
  assert_jq "H1 http.request.params 长度恰为阈值 $threshold" "$(tag_values "$spans" http.request.params)" \
    '[.[] | length] == [$t]' --argjson t "$threshold"

  log ""
  log "---- 断言 I: 运行时开关(关断:span 在、参数与响应体 tag 消失;恢复:重新采集)----"
  http_post "/httpclient/collect/toggle?enable=false" >/dev/null
  statRaw=$(http_get "/httpclient/collect/statistic")
  assert_jq "I1 关断生效 overrideCollectHttpParams=false" "$statRaw" '.raw.overrideCollectHttpParams == false'

  r=$(http_get "/api/hutool-demo/post-form?value=$off_marker&phase=off")
  assert_jq "I2 关断后业务侧调用与读 body 仍正常(开关不影响业务)" "$r" \
    '((.businessBody // "") | contains($m))' --arg m "value=$off_marker"
  sleep 3
  stat=$(http_get "/statistic")
  spans=$(exit_spans "$stat" "/api/hutool-demo/echo/off")
  assert_jq "I3 关断后出口 span 仍在(链路结构不变)" "$spans" \
    "length == 1 and .[0].componentId == $HUTOOL_COMPONENT_ID"
  assert_jq "I4 关断后无 http.request.params tag" "$(tag_values "$spans" http.request.params)" 'length == 0'
  assert_jq "I5 关断后无 http.response.body tag(响应体采集同属 override 开关)" "$(tag_values "$spans" http.response.body)" 'length == 0'

  http_post "/httpclient/collect/toggle?enable=true" >/dev/null
  statRaw=$(http_get "/httpclient/collect/statistic")
  assert_jq "I6 恢复生效 overrideCollectHttpParams=true" "$statRaw" '.raw.overrideCollectHttpParams == true'

  http_get_or_empty "/api/hutool-demo/post-form?value=$on_marker&phase=on" >/dev/null
  sleep 3
  stat=$(http_get "/statistic")
  spans=$(exit_spans "$stat" "/api/hutool-demo/echo/on")
  assert_jq "I7 恢复后重新采到 http.request.params=form=value=$on_marker" "$(tag_values "$spans" http.request.params)" \
    '[.[] | select(contains($m))] | length == 1' --arg m "form=value=$on_marker"
  assert_jq "I8 恢复后重新采到 http.response.body" "$(tag_values "$spans" http.response.body)" 'length == 1'
}
