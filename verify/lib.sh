#!/usr/bin/env bash
# verify/lib.sh — 场景验证回路的共享助手(由 run-scenario.sh 与各场景 checks.sh source)。
# 只依赖 bash + curl + jq。断言计数经 FAILURES/PASSES 全局回传给 run-scenario.sh 决定退出码。

set -o pipefail

FAILURES=${FAILURES:-0}
PASSES=${PASSES:-0}
BASE_URL=${BASE_URL:-http://127.0.0.1:9600}

log() { printf '%s\n' "$*"; }

pass() {
  PASSES=$((PASSES + 1))
  printf '  [PASS] %s\n' "$1"
}

fail() {
  FAILURES=$((FAILURES + 1))
  printf '  [FAIL] %s\n         %s\n' "$1" "$2"
}

assert_eq() {
  local name=$1 expected=$2 actual=$3
  if [[ "$expected" == "$actual" ]]; then pass "$name"; else fail "$name" "expected '$expected', got '$actual'"; fi
}

assert_ge() {
  local name=$1 min=$2 actual=$3
  if [[ "$actual" =~ ^-?[0-9]+$ ]] && (( actual >= min )); then pass "$name"; else fail "$name" "expected >= $min, got '$actual'"; fi
}

assert_le() {
  local name=$1 max=$2 actual=$3
  if [[ "$actual" =~ ^-?[0-9]+$ ]] && (( actual <= max )); then pass "$name"; else fail "$name" "expected <= $max, got '$actual'"; fi
}

# assert_jq name json expr —— expr 求值为字符串 true 则通过。
assert_jq() {
  local name=$1 json=$2 expr=$3 out
  out=$(printf '%s' "$json" | jq -r "$expr" 2>/dev/null)
  if [[ "$out" == "true" ]]; then pass "$name"; else fail "$name" "jq '$expr' => ${out:-<error>}"; fi
}

http_get() { curl -fsS --max-time 30 "$BASE_URL$1"; }
http_get_or_empty() { curl -sS --max-time 30 "$BASE_URL$1" 2>/dev/null || true; }
http_post() { curl -sS --max-time 30 -X POST "$BASE_URL$1" 2>/dev/null || true; }
http_code() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$BASE_URL$1" 2>/dev/null || true; }

# wait_ready <url> <seconds> —— 传入的是完整 URL(不是 path),故直接 curl,不再经 http_code 拼 BASE_URL。
wait_ready() {
  local url=$1 seconds=${2:-120} i code
  for ((i = 0; i < seconds; i++)); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$url" 2>/dev/null || true)
    [[ "$code" == "200" ]] && return 0
    sleep 1
  done
  return 1
}
