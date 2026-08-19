#!/usr/bin/env bash
set -euo pipefail

# Measure how long a second JVM can keep serving an old L1 shop value after
# another JVM updates the shop and evicts only its own local cache.
#
# This script intentionally mutates one shop name through the real API. It is
# guarded by ALLOW_DATA_MUTATION=true and restores ORIGINAL_NAME before exit.

APP_A_URL="${APP_A_URL:-http://127.0.0.1:8081}"
APP_B_URL="${APP_B_URL:-http://127.0.0.1:8082}"
SHOP_ID="${SHOP_ID:-1}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-0.25}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-15}"
EXPERIMENT_NAME="${EXPERIMENT_NAME:-可靠性缓存一致性实验-$(date +%Y%m%d-%H%M%S)}"
ORIGINAL_NAME="${ORIGINAL_NAME:-}"
ALLOW_DATA_MUTATION="${ALLOW_DATA_MUTATION:-false}"

if [[ "$ALLOW_DATA_MUTATION" != "true" ]]; then
  echo "拒绝执行：该脚本会通过 PUT /shop 临时修改 tb_shop.name。" >&2
  echo "请显式设置 ALLOW_DATA_MUTATION=true，并传入 ORIGINAL_NAME 用于回滚。" >&2
  exit 2
fi

if [[ -z "$ORIGINAL_NAME" ]]; then
  echo "拒绝执行：必须传入 ORIGINAL_NAME，例如 ORIGINAL_NAME='103茶餐厅'。" >&2
  exit 2
fi

if [[ "$ORIGINAL_NAME" == *\"* || "$EXPERIMENT_NAME" == *\"* ]]; then
  echo "当前脚本只接受不包含双引号的名称，避免拼接 JSON 时产生歧义。" >&2
  exit 2
fi

tmp_dir="$(mktemp -d)"
restore_needed=false

extract_name() {
  /usr/bin/ruby -rjson -e '
    payload = JSON.parse(STDIN.read)
    unless payload["success"] == true && payload["data"].is_a?(Hash) && payload["data"]["name"].is_a?(String)
      abort("接口未返回成功的商铺详情：" + payload.inspect)
    end
    puts payload["data"]["name"]
  '
}

now_seconds() {
  /usr/bin/perl -MTime::HiRes=time -e 'printf "%.6f\n", time'
}

elapsed_seconds() {
  /usr/bin/perl -MTime::HiRes=time -e 'printf "%.3f\n", time - $ARGV[0]' "$1"
}

put_shop_name() {
  local target_url="$1"
  local target_name="$2"
  curl -fsS --max-time 5 \
    -X PUT "$target_url/shop" \
    -H 'Content-Type: application/json' \
    -d "{\"id\":$SHOP_ID,\"name\":\"$target_name\"}" \
    > "$tmp_dir/last-put-response.json"
}

get_shop_name() {
  local target_url="$1"
  curl -fsS --max-time 5 "$target_url/shop/$SHOP_ID" | extract_name
}

wait_for_name() {
  local target_url="$1"
  local expected_name="$2"
  local started_at
  local deadline_at
  local current_name

  started_at="$(now_seconds)"
  deadline_at="$(/usr/bin/perl -e 'printf "%.6f\n", $ARGV[0] + $ARGV[1]' "$started_at" "$TIMEOUT_SECONDS")"

  while /usr/bin/perl -e 'exit($ARGV[0] <= $ARGV[1] ? 0 : 1)' "$(now_seconds)" "$deadline_at"; do
    current_name="$(get_shop_name "$target_url")"
    if [[ "$current_name" == "$expected_name" ]]; then
      elapsed_seconds "$started_at"
      return 0
    fi
    sleep "$POLL_INTERVAL_SECONDS"
  done

  echo "等待 $target_url/shop/$SHOP_ID 收敛到 [$expected_name] 超时，最后读到 [$current_name]" >&2
  return 1
}

cleanup() {
  if [[ "$restore_needed" == "true" ]]; then
    put_shop_name "$APP_A_URL" "$ORIGINAL_NAME" >/dev/null || true
  fi
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

echo "实验对象：shopId=$SHOP_ID"
echo "A 更新端：$APP_A_URL"
echo "B 观测端：$APP_B_URL"
echo "原始名称：$ORIGINAL_NAME"
echo "实验名称：$EXPERIMENT_NAME"

echo "预热 A/B 的 L1 缓存..."
before_a="$(get_shop_name "$APP_A_URL")"
before_b="$(get_shop_name "$APP_B_URL")"
echo "预热后 A 读到：$before_a"
echo "预热后 B 读到：$before_b"

if [[ "$before_a" != "$ORIGINAL_NAME" || "$before_b" != "$ORIGINAL_NAME" ]]; then
  echo "拒绝执行：预热读到的名称与 ORIGINAL_NAME 不一致，请先确认数据库状态。" >&2
  exit 3
fi

echo "通过 A 执行真实 PUT /shop 更新..."
put_shop_name "$APP_A_URL" "$EXPERIMENT_NAME"
restore_needed=true

echo "轮询 B，测量旧 L1 收敛到新值的时间..."
to_new_seconds="$(wait_for_name "$APP_B_URL" "$EXPERIMENT_NAME")"
if [[ -z "$to_new_seconds" ]]; then
  echo "收敛耗时为空，拒绝继续记录无效实验结果。" >&2
  exit 4
fi
echo "B 收敛到新值耗时：${to_new_seconds}s"

echo "通过 A 恢复原始名称..."
put_shop_name "$APP_A_URL" "$ORIGINAL_NAME"
restore_needed=false

echo "轮询 B，确认恢复值也能收敛..."
to_original_seconds="$(wait_for_name "$APP_B_URL" "$ORIGINAL_NAME")"
if [[ -z "$to_original_seconds" ]]; then
  echo "恢复耗时为空，拒绝继续记录无效实验结果。" >&2
  exit 4
fi
echo "B 收敛回原始值耗时：${to_original_seconds}s"

echo "实验完成：update_to_new=${to_new_seconds}s, restore_to_original=${to_original_seconds}s"
