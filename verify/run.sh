#!/usr/bin/env bash
# verify/run.sh — 验证回路宿主入口(Linux / WSL / Git-Bash / CI 通用)。
#
# 一条命令:构建运行器镜像一次 -> 跑一个/全部场景(或版本矩阵)-> 聚合退出码。
#
# 用法:
#   bash verify/run.sh                                  # 跑全部场景(各自默认依赖版本)
#   bash verify/run.sh --scenario logfile-reporter      # 只跑某场景
#   bash verify/run.sh --scenario override-httpclient --matrix   # 跑版本矩阵
#   bash verify/run.sh --list                           # 列出场景
#
# 版本矩阵:场景的 support-version.list 每行一个版本,--matrix 时以
# -D<场景声明的 MATRIX_PROPERTY>=<版本> 构建 demo-app(见 scenario.conf)。
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$SCRIPT_DIR/.." && pwd)
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yaml"
SCENARIOS_DIR="$SCRIPT_DIR/scenarios"

usage() {
  sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

scenario_args=()
run_all=true
matrix=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario) scenario_args+=("$2"); run_all=false; shift 2 ;;
    --matrix) matrix=true; shift ;;
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

echo "[..] 构建 verify 运行器镜像"
if ! docker compose -f "$COMPOSE_FILE" build verify; then
  echo "[FAIL] 运行器镜像构建失败"
  exit 1
fi

declare -a failed=()
for scenario in "${scenario_args[@]}"; do
  versions=("")
  list_file="$SCENARIOS_DIR/$scenario/support-version.list"
  if [[ "$matrix" == "true" && -f "$list_file" ]]; then
    mapfile -t versions < <(grep -vE '^[[:space:]]*(#|$)' "$list_file")
    # 矩阵版本注入的 maven 属性名由场景声明(见 scenario.conf 的 MATRIX_PROPERTY)
    matrix_property=$(sed -n 's/^[[:space:]]*MATRIX_PROPERTY="\{0,1\}\([^"]*\)"\{0,1\}[[:space:]]*$/\1/p' \
      "$SCENARIOS_DIR/$scenario/scenario.conf" | head -n 1)
    [[ -n "$matrix_property" ]] || matrix_property="httpclient.version"
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
