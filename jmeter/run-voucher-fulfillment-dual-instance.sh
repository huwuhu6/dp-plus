#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "用法: bash jmeter/run-voucher-fulfillment-dual-instance.sh <certificateNo> <token>"
  exit 1
fi

certificate_no="$1"
token="$2"
operator_id="20260820001"
shop_id="1"
requests_per_instance=10

run_instance() {
  local port="$1"
  local instance="$2"
  local index
  for index in $(seq 1 "$requests_per_instance"); do
    curl -fsS --max-time 10 -X POST "http://127.0.0.1:${port}/voucher-fulfillment/certificates/${certificate_no}/verify" \
      -H "Content-Type: application/json" \
      -H "authorization: ${token}" \
      -d "{\"requestId\":\"dual-instance-${instance}-${index}-$(date +%s%N)\",\"operatorId\":${operator_id},\"shopId\":${shop_id}}" \
      >/dev/null
  done
}

run_instance 8083 instance-a &
pid_a=$!
run_instance 8084 instance-b &
pid_b=$!
wait "$pid_a"
wait "$pid_b"

echo "已向 8083 与 8084 各发送 ${requests_per_instance} 次同券核销请求。"
echo "请查询 MySQL：应为 VERIFY/SUCCESS=1、VERIFY/REJECTED=19，券状态为 USED。"
