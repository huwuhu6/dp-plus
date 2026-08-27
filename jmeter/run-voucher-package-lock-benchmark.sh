#!/usr/bin/env bash
set -euo pipefail

jmeter_bin="${JMETER_BIN:-/Users/me/Downloads/apache-jmeter-5.6.3/bin/jmeter}"
mysql_bin="${MYSQL_BIN:-mysql}"
redis_cli="${REDIS_CLI:-redis-cli}"
host="${BENCHMARK_HOST:-127.0.0.1}"
ports_csv="${BENCHMARK_PORTS:-8083}"
user_id_base="${BENCHMARK_USER_ID_BASE:-202608270000}"
shop_id=1
voucher_id=1
voucher_count_per_order=10
single_voucher_price=4750
scenarios=(5 10 20 50)
run_id="$(date +%Y%m%d%H%M%S)-$$"
result_root="jmeter/results/voucher-package-lock-benchmark-${run_id}"

if [[ ! -x "$jmeter_bin" ]]; then
  echo "找不到 JMeter: $jmeter_bin" >&2
  exit 1
fi

IFS=',' read -r -a ports <<< "$ports_csv"
if [[ ${#ports[@]} -eq 0 || -z "${ports[0]}" ]]; then
  echo "BENCHMARK_PORTS 至少需要一个端口" >&2
  exit 1
fi

mkdir -p "$result_root"
printf 'concurrency,orders,requests,success,rejected,avg_ms,p95_ms,p99_ms,max_ms,throughput_req_per_s\n' > "$result_root/metrics.csv"

create_login_token() {
  local token="$1"
  local user_id="$2"
  "$redis_cli" HSET "login:token:${token}" id "$user_id" nickName "voucher-benchmark-${user_id}" >/dev/null
  "$redis_cli" EXPIRE "login:token:${token}" 1800 >/dev/null
}

create_package() {
  local token="$1"
  local user_id="$2"
  local paid_amount="$3"
  curl -fsS --max-time 10 -X POST "http://${host}:${ports[0]}/voucher-fulfillment/packages" \
    -H "Content-Type: application/json" \
    -H "authorization: ${token}" \
    -d "{\"userId\":${user_id},\"voucherId\":${voucher_id},\"shopId\":${shop_id},\"quantity\":${voucher_count_per_order},\"paidAmount\":${paid_amount}}"
}

calculate_metrics() {
  local jtl="$1"
  local elapsed_file="$2"
  awk -F, 'NR > 1 { print $2 }' "$jtl" | LC_ALL=C sort -n > "$elapsed_file"
  local total success rejected average maximum p95_index p99_index p95 p99 throughput
  total="$(wc -l < "$elapsed_file" | tr -d ' ')"
  success="$(awk -F, 'NR > 1 && $8 == "true" { count++ } END { print count + 0 }' "$jtl")"
  rejected=$((total - success))
  average="$(awk '{ sum += $1; count++ } END { if (count) printf "%.2f", sum / count; else print "0" }' "$elapsed_file")"
  maximum="$(tail -n 1 "$elapsed_file")"
  p95_index=$(( (total * 95 + 99) / 100 ))
  p99_index=$(( (total * 99 + 99) / 100 ))
  p95="$(sed -n "${p95_index}p" "$elapsed_file")"
  p99="$(sed -n "${p99_index}p" "$elapsed_file")"
  throughput="$(awk -F, 'NR > 1 { start = NR == 2 || $1 < start ? $1 : start; end = $1 + $2 > end ? $1 + $2 : end; count++ } END { if (end > start) printf "%.2f", count * 1000 / (end - start); else print "0" }' "$jtl")"
  printf '%s,%s,%s,%s,%s,%s,%s,%s\n' "$total" "$success" "$rejected" "$average" "$p95" "$p99" "$maximum" "$throughput"
}

validate_database() {
  local orders_file="$1"
  local validation_file="$2"
  printf 'order_id,expected_used,expected_status,actual_status,persisted_used_count,persisted_refunded_count,voucher_used_count,voucher_refunded_count,unused_count,verify_success,distinct_success_certificate,valid\n' > "$validation_file"
  while IFS=, read -r order_id expected_used expected_status; do
    local row actual_status persisted_used persisted_refunded used refunded unused verify_success distinct_success expected_unused
    row="$("$mysql_bin" -N -uroot hmdp -e "SELECT o.status, o.used_count, o.refunded_count, SUM(c.status='USED'), SUM(c.status='REFUNDED'), SUM(c.status='UNUSED'), SUM(a.action='VERIFY' AND a.result='SUCCESS'), COUNT(DISTINCT CASE WHEN a.action='VERIFY' AND a.result='SUCCESS' THEN a.certificate_id END) FROM tb_voucher_package_order o JOIN tb_voucher_certificate c ON c.package_order_id=o.id LEFT JOIN tb_voucher_fulfillment_audit a ON a.certificate_id=c.id WHERE o.id=${order_id} GROUP BY o.id,o.status,o.used_count,o.refunded_count;")"
    IFS=$'\t' read -r actual_status persisted_used persisted_refunded used refunded unused verify_success distinct_success <<< "$row"
    expected_unused=$((voucher_count_per_order - expected_used))
    if [[ "$actual_status" != "$expected_status" || "$persisted_used" != "$expected_used" || "$persisted_refunded" != "0" || "$used" != "$expected_used" || "$refunded" != "0" || "$unused" != "$expected_unused" || "$verify_success" != "$expected_used" || "$distinct_success" != "$expected_used" ]]; then
      printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,false\n' "$order_id" "$expected_used" "$expected_status" "$actual_status" "$persisted_used" "$persisted_refunded" "$used" "$refunded" "$unused" "$verify_success" "$distinct_success" >> "$validation_file"
      echo "数据库业务校验失败，详情见 $validation_file" >&2
      exit 1
    fi
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,true\n' "$order_id" "$expected_used" "$expected_status" "$actual_status" "$persisted_used" "$persisted_refunded" "$used" "$refunded" "$unused" "$verify_success" "$distinct_success" >> "$validation_file"
  done < "$orders_file"
}

scenario_index=0
for concurrency in "${scenarios[@]}"; do
  scenario_index=$((scenario_index + 1))
  scenario_dir="${result_root}/concurrency-${concurrency}"
  certificate_csv="${scenario_dir}/thread-data.csv"
  orders_file="${scenario_dir}/orders.csv"
  jtl="${scenario_dir}/result.jtl"
  mkdir -p "$scenario_dir"
  : > "$certificate_csv"
  : > "$orders_file"

  remaining="$concurrency"
  order_index=0
  row_index=0
  while [[ "$remaining" -gt 0 ]]; do
    order_index=$((order_index + 1))
    user_id=$((user_id_base + scenario_index * 100 + order_index))
    token="voucher-benchmark-${run_id}-${concurrency}-${order_index}"
    create_login_token "$token" "$user_id"
    response="$(create_package "$token" "$user_id" $((voucher_count_per_order * single_voucher_price)))"
    if [[ "$(printf '%s' "$response" | jq -r '.success')" != "true" ]]; then
      echo "创建 ${concurrency} 并发场景的测试订单失败: $response" >&2
      exit 1
    fi
    first_certificate="$(printf '%s' "$response" | jq -r '.data[0]')"
    order_id="$("$mysql_bin" -N -uroot hmdp -e "SELECT package_order_id FROM tb_voucher_certificate WHERE certificate_no='${first_certificate}';")"
    take="$voucher_count_per_order"
    if [[ "$remaining" -lt "$take" ]]; then take="$remaining"; fi
    expected_status="USED"
    if [[ "$take" -lt "$voucher_count_per_order" ]]; then expected_status="PARTIALLY_USED"; fi
    printf '%s,%s,%s\n' "$order_id" "$take" "$expected_status" >> "$orders_file"

    certificate_index=0
    while IFS= read -r certificate_no; do
      if [[ "$certificate_index" -ge "$take" ]]; then break; fi
      port_index=$((row_index % ${#ports[@]}))
      printf '%s,%s,%s,%s\n' "$certificate_no" "$token" "$user_id" "${ports[$port_index]}" >> "$certificate_csv"
      certificate_index=$((certificate_index + 1))
      row_index=$((row_index + 1))
    done < <(printf '%s' "$response" | jq -r '.data[]')
    remaining=$((remaining - take))
  done

  "$jmeter_bin" -n -t jmeter/voucher-package-order-lock-benchmark.jmx -l "$jtl" \
    -Jhost="$host" -Jthreads="$concurrency" -JcertificateCsv="$certificate_csv" \
    -Jjmeter.save.saveservice.output_format=csv -Jjmeter.save.saveservice.print_field_names=true
  IFS=, read -r total success rejected average p95 p99 maximum throughput < <(calculate_metrics "$jtl" "${scenario_dir}/elapsed-ms.txt")
  if [[ "$total" != "$concurrency" || "$success" != "$concurrency" || "$rejected" != "0" ]]; then
    echo "JMeter 业务断言失败: concurrency=${concurrency}, total=${total}, success=${success}, rejected=${rejected}" >&2
    exit 1
  fi
  validate_database "$orders_file" "${scenario_dir}/database-validation.csv"
  orders_count="$(wc -l < "$orders_file" | tr -d ' ')"
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' "$concurrency" "$orders_count" "$total" "$success" "$rejected" "$average" "$p95" "$p99" "$maximum" "$throughput" >> "$result_root/metrics.csv"
done

echo "基线完成：$result_root"
cat "$result_root/metrics.csv"
