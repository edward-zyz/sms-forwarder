#!/usr/bin/env bash
# TLA+ 形式化模型一键验证脚本
# 用法：bash formal/verify.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
FORMAL_DIR="$ROOT_DIR/formal"
CACHE_DIR="$FORMAL_DIR/.cache"
JAR_PATH="$CACHE_DIR/tla2tools.jar"
TLA_VERSION="v1.8.0"
TLA_URL="https://github.com/tlaplus/tlaplus/releases/download/${TLA_VERSION}/tla2tools.jar"
EXPECTED_SHA256="67f9575c3b9ef7a0787b1bded34812862ffe79d711298e5a75b1a77720b19928"

# 定位 Java 运行时（优先 JAVA_HOME，再检查 private/config/android-env.sh，最后回退系统 java）
if [[ -z "${JAVA_HOME:-}" && -f "$ROOT_DIR/private/config/android-env.sh" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT_DIR/private/config/android-env.sh"
fi

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
else
  echo "[formal] 错误：未找到可用的 Java 运行时，请配置 JAVA_HOME（推荐 JDK 17+）。" >&2
  exit 1
fi

mkdir -p "$CACHE_DIR"

verify_sha256() {
  local file="$1"
  local actual
  if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$file" | awk '{print $1}')"
  else
    actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  fi
  [[ "$actual" == "$EXPECTED_SHA256" ]]
}

if [[ ! -f "$JAR_PATH" ]] || ! verify_sha256 "$JAR_PATH"; then
  echo "[formal] 正在下载 TLA+ 工具包 (${TLA_VERSION})..."
  curl -fSL "$TLA_URL" -o "$JAR_PATH.tmp"
  if ! verify_sha256 "$JAR_PATH.tmp"; then
    rm -f "$JAR_PATH.tmp"
    echo "[formal] 错误：tla2tools.jar SHA-256 校验失败，已中止。" >&2
    exit 1
  fi
  mv "$JAR_PATH.tmp" "$JAR_PATH"
fi

MODELS=(
  "OutboxForwarding"
  "ServiceReadiness"
  "ConfigPersistence"
  "FilterEditor"
  "RuleWizard"
  "IosForwardPipeline"
)

META_DIR="$CACHE_DIR/states"
mkdir -p "$META_DIR"

echo "============================================================"
echo " TLA+ 形式化验证：共 ${#MODELS[@]} 个状态机模型"
echo "============================================================"

for model in "${MODELS[@]}"; do
  echo ""
  echo ">>> 验证模型: ${model}"
  rm -rf "${META_DIR:?}/${model}"
  LOG_FILE="$META_DIR/${model}.log"
  "$JAVA_BIN" -XX:+UseParallelGC -cp "$JAR_PATH" tlc2.TLC \
    -workers auto \
    -cleanup \
    -metadir "$META_DIR/$model" \
    -coverage 1 \
    -config "$FORMAL_DIR/${model}.cfg" \
    "$FORMAL_DIR/${model}.tla" | tee "$LOG_FILE"

  # 校验不存在 0:0 零覆盖的不可达动作（保证每个声明动作与状态路径均已验证）
  if grep -Eq '^<[A-Za-z0-9_]+ line .*: 0:0$' "$LOG_FILE"; then
    echo "[formal] 错误：模型 ${model} 存在零覆盖（不可达）动作：" >&2
    grep -En '^<[A-Za-z0-9_]+ line .*: 0:0$' "$LOG_FILE" >&2
    exit 1
  fi
done

echo ""
echo "============================================================"
echo " 全部 ${#MODELS[@]} 个 TLA+ 状态机模型验证通过（0 违规，0 死锁）"
echo "============================================================"
