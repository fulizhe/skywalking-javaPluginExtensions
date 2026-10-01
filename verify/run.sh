#!/usr/bin/env bash
# verify/run.sh — 验证回路宿主入口(Linux / WSL / Git-Bash / CI 通用)。
#
# 一条命令:构建运行器镜像一次 -> 跑一个/全部场景(或版本矩阵)-> 聚合退出码。
#
# 用法:
#   bash verify/run.sh                                  # 跑全部场景(各自默认依赖版本)
#   bash verify/run.sh --scenario logfile-reporter      # 只跑某场景
#   bash verify/run.sh --scenario override-httpclient --matrix   # 跑版本矩阵(全部版本)
#   bash verify/run.sh --scenario override-hutool --version 5.8.47   # 只跑指定版本
#   bash verify/run.sh --list                           # 列出场景
#
# 环境变量:
#   VERIFY_SKIP_BUILD=1  跳过运行器镜像构建(镜像已备好:CI 的 build-runner job 已推到 GHCR)
#
# 版本矩阵:场景的 support-version.list 每行一个版本,--matrix 时以
# -D<场景声明的 MATRIX_PROPERTY>=<版本> 构建 demo-app(见 scenario.conf)。
# --version <v> 只跑那一个版本(传空串等于不给);CI 的 matrix 就是靠它把
# 「场景 × 版本」摊成并行 job,所以它得跟 --scenario 一起用。
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$SCRIPT_DIR/.." && pwd)
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yaml"
SCENARIOS_DIR="$SCRIPT_DIR/scenarios"

usage() {
  # 打印头部注释(第 2 行起,到第一个非 # 行为止),去掉行首 "# "。
  # 别再写死行号区间 —— 加一行说明就得跟着改,漏改就把 set -uo pipefail 打进 --help。
  awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "${BASH_SOURCE[0]}"
}

# 矩阵版本注入的 maven 属性名由场景声明(见 scenario.conf);未声明则兜底
matrix_property_of() {
  local conf="$SCENARIOS_DIR/$1/scenario.conf" prop=""
  [[ -f "$conf" ]] || return 0
  prop=$(sed -n 's/^[[:space:]]*MATRIX_PROPERTY="\{0,1\}\([^"]*\)"\{0,1\}[[:space:]]*$/\1/p' "$conf" | head -n 1)
  [[ -n "$prop" ]] || prop="httpclient.version"
  printf '%s' "$prop"
}

scenario_args=()
run_all=true
matrix=false
version_override=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario) scenario_args+=("$2"); run_all=false; shift 2 ;;
    --matrix) matrix=true; shift ;;
    --version)
      [[ $# -ge 2 ]] || { echo "--version 需要一个值(可传空串)" >&2; exit 64; }
      version_override="$2"; shift 2 ;;
    --list)
      for d in "$SCENARIOS_DIR"/*/; do [[ -d "$d" ]] && basename "$d"; done
      exit 0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 64 ;;
  esac
done

if [[ "$run_all" == "true" ]]; then
  for d in "$SCENARIOS_DIR"/*/; do
    [[ -d "$d" ]] && scenario_args+=("$(basename "$d")")
  done
fi
if [[ ${#scenario_args[@]} -eq 0 ]]; then
  echo "no scenarios found under $SCENARIOS_DIR" >&2
  exit 2
fi

# --version 早失败:别等镜像构建完、demo-app 编译完才发现场景压根没有版本概念
if [[ -n "$version_override" ]]; then
  if [[ "$run_all" == "true" ]]; then
    echo "--version 需要配合 --scenario <name>" >&2
    exit 64
  fi
  for scenario in "${scenario_args[@]}"; do
    if [[ ! -f "$SCENARIOS_DIR/$scenario/support-version.list" ]]; then
      echo "场景 $scenario 没有 support-version.list,不支持 --version" >&2
      exit 64
    fi
  done
fi

if [[ "${VERIFY_SKIP_BUILD:-0}" == "1" ]]; then
  echo "[..] VERIFY_SKIP_BUILD=1,跳过运行器镜像构建(镜像应已备好)"
else
  echo "[..] 构建 verify 运行器镜像"
  if ! docker compose -f "$COMPOSE_FILE" build verify; then
    echo "[FAIL] 运行器镜像构建失败"
    exit 1
  fi
fi

declare -a failed=()
for scenario in "${scenario_args[@]}"; do
  versions=("")
  list_file="$SCENARIOS_DIR/$scenario/support-version.list"
  if [[ -n "$version_override" ]]; then
    # 只跑这一个版本(CI matrix 的每个 job 走这条)
    versions=("$version_override")
    matrix_property=$(matrix_property_of "$scenario")
  elif [[ "$matrix" == "true" && -f "$list_file" ]]; then
    mapfile -t versions < <(grep -vE '^[[:space:]]*(#|$)' "$list_file")
    matrix_property=$(matrix_property_of "$scenario")
  else
    matrix_property=""
  fi
  for version in "${versions[@]}"; do
    label="$scenario${version:+ ($matrix_property=$version)}"
    echo ""
    echo "===== 运行场景: $label ====="
    SCENARIO="$scenario" MATRIX_PROPERTY="$matrix_property" MATRIX_VERSION="$version" \
      docker compose -f "$COMPOSE_FILE" run --rm verify
    code=$?
    if [[ $code -ne 0 ]]; then failed+=("$label (exit $code)"); fi
  done
done

echo ""
if [[ ${#failed[@]} -eq 0 ]]; then
  echo "===== verify 全绿 ====="
  exit 0
fi
echo "===== verify 失败 ====="
for f in "${failed[@]}"; do echo "  - $f"; done
exit 1
