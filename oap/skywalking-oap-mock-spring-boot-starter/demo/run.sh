#!/usr/bin/env bash
# mock-oap-demo 一键启动:定位 agent -> 需要时构建 -> 挂 agent 启动。
#
# 用法:
#   bash run.sh /path/to/skywalking-agent/skywalking-agent.jar
#   SKYWALKING_AGENT_JAR=/path/to/skywalking-agent.jar bash run.sh
#   bash run.sh          # 交互式提示输入 agent 路径
#   (如需 ./run.sh 执行,先 chmod +x run.sh)
set -euo pipefail
cd "$(dirname "$0")"

echo
echo "=== mock-oap-demo 一键启动 ==="
echo

# ---------- 1. 定位 SkyWalking agent ----------
AGENT="${1:-${SKYWALKING_AGENT_JAR:-}}"

if [ -z "$AGENT" ]; then
    for candidate in \
        "skywalking-agent/skywalking-agent.jar" \
        "../skywalking-agent/skywalking-agent.jar" \
        "$HOME/skywalking-agent/skywalking-agent.jar"
    do
        if [ -f "$candidate" ]; then
            AGENT="$candidate"
            break
        fi
    done
fi

# 允许直接传 agent 目录
if [ -n "$AGENT" ] && [ -d "$AGENT" ] && [ -f "$AGENT/skywalking-agent.jar" ]; then
    AGENT="$AGENT/skywalking-agent.jar"
fi

if [ -z "$AGENT" ] && [ -t 0 ]; then
    printf 'SkyWalking agent 路径(jar 或所在目录): '
    read -r AGENT
    if [ -n "$AGENT" ] && [ -d "$AGENT" ] && [ -f "$AGENT/skywalking-agent.jar" ]; then
        AGENT="$AGENT/skywalking-agent.jar"
    fi
fi

if [ -z "$AGENT" ] || [ ! -f "$AGENT" ]; then
    cat >&2 <<'EOF'
没找到 SkyWalking agent。请把 agent 解压到任意目录,然后任选一种方式:
  1. 运行时传入:  bash run.sh /path/to/skywalking-agent/skywalking-agent.jar
  2. 设置环境变量: SKYWALKING_AGENT_JAR=/path/to/skywalking-agent.jar bash run.sh
  3. 直接把 agent 目录放到 demo/skywalking-agent/

下载: https://skywalking.apache.org/downloads/
EOF
    exit 1
fi

# ---------- 2. 需要时构建 ----------
JAR="target/mock-oap-demo-2.0.0.jar"

# 强制重建: MOCK_OAP_REBUILD=1 bash run.sh ...
FORCE_BUILD="${MOCK_OAP_REBUILD:-}"
if [ ! -f "$JAR" ]; then
    FORCE_BUILD=1
elif [ -n "$(find pom.xml src -type f -newer "$JAR" -print -quit 2>/dev/null)" ]; then
    FORCE_BUILD=1
fi

if [ -n "$FORCE_BUILD" ]; then
    if ! command -v mvn >/dev/null 2>&1; then
        echo "[ERROR] 没有找到 mvn。请先安装 Maven,或手动构建:" >&2
        echo "    cd .. && mvn -B install -DskipTests" >&2
        echo "    cd demo && mvn -B package" >&2
        exit 1
    fi
    echo "[1/2] 构建 starter ..."
    (cd .. && mvn -B -q -DskipTests install)
    echo "[2/2] 构建 demo ..."
    mvn -B -q -DskipTests package
fi

if [ ! -f "$JAR" ]; then
    echo "[ERROR] 没有找到 $JAR" >&2
    exit 1
fi

# ---------- 3. 启动 ----------
echo
echo "agent : $AGENT"
echo "jar   : $JAR"
echo "页面  : http://localhost:9600/   (演示导航)"
echo "提示  : 启动后等 30 秒左右再生成流量(agent 需要先连上 collector)"
echo "停止  : Ctrl+C"
echo

exec env \
    SW_AGENT_NAME=mock-oap-demo \
    SW_AGENT_COLLECTOR_BACKEND_SERVICES=127.0.0.1:11800 \
    java -javaagent:"$AGENT" -jar "$JAR"
