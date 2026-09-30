#!/usr/bin/env bash
# verify/run-scenario.sh — 容器内场景入口(由 verify/Dockerfile 的 ENTRYPOINT 调用)。
#
# 环境变量:
#   SCENARIO            场景名(verify/scenarios/<name>),默认 logfile-reporter
#   MATRIX_VERSION      可选;非空时以 -D<场景声明的 MATRIX_PROPERTY>=<v> 构建 demo-app(版本矩阵)
#   MATRIX_PROPERTY     可选;兜底值,场景在 scenario.conf 里声明 MATRIX_PROPERTY 为准
#   REPO                仓库挂载点,默认 /src
#   WebPort             demo-app 端口,默认 9600
#
# 退出码:0 全绿;1 构建失败;2 场景缺失/应用未就绪;3 插件未加载;5 断言失败。
set -uo pipefail

REPO=${REPO:-/src}
SCENARIO=${SCENARIO:-logfile-reporter}
SCENARIO_DIR="$REPO/verify/scenarios/$SCENARIO"
SETTINGS="$REPO/agent/settings.xml"
JAVA17_HOME=${JAVA17_HOME:-/opt/jdk17}
JAVA8_HOME=${JAVA8_HOME:-/opt/java/openjdk}
AGENT_SRC=${AGENT_SRC:-/opt/skywalking-agent}
AGENT_RUN=/tmp/skywalking-agent
APP_JAR="$REPO/agent/demo-app/target/demo-app-1.0.0.jar"
APP_LOG=/tmp/demo-app.log
WebPort=${WebPort:-9600}

source "$REPO/verify/lib.sh"

if [[ ! -f "$SCENARIO_DIR/scenario.conf" ]]; then
  log "[FAIL] 场景不存在: $SCENARIO (期望 $SCENARIO_DIR/scenario.conf)"
  exit 2
fi

# 场景默认值,由 scenario.conf 覆盖
PLUGIN_MODULES=""
PLUGIN_JAR_GLOBS=""
PLUGIN_LOAD_REGEX=""
HEALTH_PATH="/"
AGENT_OPTS=""
# 官方插件移除:空格分隔的 jar 通配(相对 agent plugins/),override 场景避免同源类被重复增强
REMOVE_OFFICIAL_PLUGINS=""
# 版本矩阵注入的 maven 属性名;场景在 scenario.conf 里声明为准(此处仅兜底直接跑容器的场景)
MATRIX_PROPERTY="${MATRIX_PROPERTY:-httpclient.version}"
# shellcheck disable=SC1090
source "$SCENARIO_DIR/scenario.conf"

export WebPort
# 矩阵版本对 checks.sh 可见:断言可按被测版本给出不同口径(如 hutool 5.4/5.8 的 body 采集差异)
export MATRIX_PROPERTY
export MATRIX_VERSION="${MATRIX_VERSION:-}"

log "==============================================================="
log " verify 场景回路: scenario=$SCENARIO ${MATRIX_VERSION:+$MATRIX_PROPERTY=$MATRIX_VERSION}"
log " repo=$REPO  WebPort=$WebPort"
log "==============================================================="

# ---- 1. 构建插件(JDK17 工具链, release 8)----
log "[..] 构建插件: mvn -pl $PLUGIN_MODULES -am"
JAVA_HOME="$JAVA17_HOME" PATH="$JAVA17_HOME/bin:$PATH" \
  mvn -B -q -ntp -s "$SETTINGS" -f "$REPO/agent/pom.xml" \
  clean package -Dmaven.test.skip=true -pl "$PLUGIN_MODULES" -am -T 2C
if [[ $? -ne 0 ]]; then log "[FAIL] 插件构建失败"; exit 1; fi

# ---- 2. 构建 demo-app(JDK8, 与既有 Dockerfile 口径一致)----
app_build_args=()
if [[ -n "${MATRIX_VERSION:-}" ]]; then app_build_args+=("-D$MATRIX_PROPERTY=$MATRIX_VERSION"); fi
log "[..] 构建 demo-app (${app_build_args[*]:-默认依赖版本})"
JAVA_HOME="$JAVA8_HOME" PATH="$JAVA8_HOME/bin:$PATH" \
  mvn -B -q -ntp -s "$SETTINGS" -f "$REPO/agent/demo-app/pom.xml" \
  clean package -DskipTests "${app_build_args[@]}"
if [[ $? -ne 0 ]]; then log "[FAIL] demo-app 构建失败"; exit 1; fi
if [[ ! -f "$APP_JAR" ]]; then log "[FAIL] demo-app jar 不存在: $APP_JAR"; exit 1; fi

# ---- 3. 装配 agent(可写副本)并安装插件 jar ----
rm -rf "$AGENT_RUN"
cp -r "$AGENT_SRC" "$AGENT_RUN"
for pattern in $REMOVE_OFFICIAL_PLUGINS; do
  # nullglob:通配无命中时不做任何事(否则字面量会传给 rm)
  shopt -s nullglob
  removed=("$AGENT_RUN"/plugins/$pattern)
  shopt -u nullglob
  for jar in "${removed[@]}"; do
    rm -f "$jar"
    log "[OK] 移除官方插件: $(basename "$jar")"
  done
done
for rel in $PLUGIN_JAR_GLOBS; do
  for jar in "$REPO"/agent/$rel; do
    if [[ ! -e "$jar" ]]; then log "[FAIL] 插件 jar 未找到: agent/$rel"; exit 1; fi
    cp "$jar" "$AGENT_RUN/plugins/"
    log "[OK] 安装插件: $(basename "$jar")"
  done
done

# ---- 4. 启动应用(JDK8 + javaagent)----
rm -f "$AGENT_RUN"/logs/skywalking-api.log "$AGENT_RUN"/logs/skywalking-agent.log
log "[..] 启动 demo-app (javaagent, WebPort=$WebPort)"
# shellcheck disable=SC2086
"$JAVA8_HOME/bin/java" \
  "-javaagent:$AGENT_RUN/skywalking-agent.jar" \
  -Dskywalking.agent.service_name="demo-app-verify" \
  $AGENT_OPTS \
  -jar "$APP_JAR" >"$APP_LOG" 2>&1 &
APP_PID=$!
trap 'kill "$APP_PID" 2>/dev/null || true' EXIT

if ! wait_ready "$BASE_URL$HEALTH_PATH" 120; then
  log "[FAIL] 应用 120 秒内未就绪, 日志尾部:"
  tail -n 30 "$APP_LOG" || true
  exit 2
fi
log "[OK] 应用就绪: $BASE_URL$HEALTH_PATH"

# ---- 5. 插件加载校验 ----
sleep 2
API_LOG="$AGENT_RUN/logs/skywalking-api.log"
if [[ -z "$PLUGIN_LOAD_REGEX" ]] || ! grep -qE "$PLUGIN_LOAD_REGEX" "$API_LOG" 2>/dev/null; then
  log "[FAIL] agent 日志未出现插件加载记录: $API_LOG"
  tail -n 20 "$API_LOG" 2>/dev/null || true
  exit 3
fi

# ---- 6. 场景断言 ----
# shellcheck disable=SC1090
source "$SCENARIO_DIR/checks.sh"
run_checks

log ""
log "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  log " 场景[$SCENARIO]全绿: PASS=$PASSES (exit 0)"
else
  log " 场景[$SCENARIO]失败: FAIL=$FAILURES PASS=$PASSES (exit 5)"
fi
log "==============================================================="

[[ "$FAILURES" -eq 0 ]] || exit 5
exit 0
