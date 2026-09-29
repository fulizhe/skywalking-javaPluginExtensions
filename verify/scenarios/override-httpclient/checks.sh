# override-httpclient 场景断言。由 verify/run-scenario.sh source 后调用 run_checks。
# 读取面:demo-app /httpclient/collect/*(开关)与 /statistic(logfile-reporter 捕获的 span/tag)。

run_checks() {
  local status stat

  log ""
  log "---- 断言 A: 采集开关读口 + 运行时开关 ----"

  status=$(http_get "/httpclient/collect/status")
  assert_jq "采集开关读口可用(enabled=true)" "$status" '.enabled == true'

  http_post "/httpclient/collect/toggle?enable=false" >/dev/null
  status=$(http_get "/httpclient/collect/status")
  assert_jq "toggle=false -> enabled=false" "$status" '.enabled == false'

  http_post "/httpclient/collect/toggle?enable=true" >/dev/null
  status=$(http_get "/httpclient/collect/status")
  assert_jq "toggle=true -> enabled=true" "$status" '.enabled == true'

  log ""
  log "---- 断言 B: httpclient 出口 span + 表单体采集为 http.request.params tag ----"

  http_get_or_empty "/api/trace-alert-demo/httpclient-post?value=verify-body" >/dev/null
  sleep 3
  stat=$(http_get "/statistic")

  assert_jq "存在 httpclient 出口 span(Exit)" "$stat" \
    '[.data[].logs[].spans[] | select(.spanType == "Exit")] | length >= 1'
  assert_jq "出口 span 带 http.request.params tag 且含表单体值" "$stat" \
    '[.data[].logs[].spans[] | select(.spanType == "Exit") | (.tagList // [])[]? | select(.["tag-key"] == "http.request.params" and ((.["tag-value"] // "") | test("verify-body")))] | length >= 1'
}
