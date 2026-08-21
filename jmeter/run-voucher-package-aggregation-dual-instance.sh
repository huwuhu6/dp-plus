#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "用法: bash jmeter/run-voucher-package-aggregation-dual-instance.sh <certificateNoA> <certificateNoB> <token>"
  exit 1
fi

certificate_a="$1"
certificate_b="$2"
token="$3"
operator_id="20260820001"
shop_id="1"
request_suffix="$(date +%s%N)"

verify() {
  local port="$1"
  local certificate_no="$2"
  local request_id="$3"
  curl -fsS --max-time 10 -X POST "http://127.0.0.1:${port}/voucher-fulfillment/certificates/${certificate_no}/verify" \
    -H "Content-Type: application/json" \
    -H "authorization: ${token}" \
    -d "{\"requestId\":\"${request_id}\",\"operatorId\":${operator_id},\"shopId\":${shop_id}}" \
    >/dev/null
}

verify 8083 "$certificate_a" "aggregate-instance-a-${request_suffix}" &
pid_a=$!
verify 8084 "$certificate_b" "aggregate-instance-b-${request_suffix}" &
pid_b=$!
wait "$pid_a"
wait "$pid_b"

echo "已由 8083、8084 分别核销同一套餐中的不同券。"
echo "请查询 MySQL：两张券均为 USED，套餐订单状态必须为 USED。"
