#!/usr/bin/env bash
set -euo pipefail

# Run the existing same-key plan against two JVM-local L1 caches at the same time.
# Results stay under the ignored jmeter/results directory.
JMETER_BIN="${JMETER_BIN:-$HOME/Downloads/apache-jmeter-5.6.3/bin/jmeter}"
THREADS_PER_INSTANCE="${THREADS_PER_INSTANCE:-100}"
SHOP_ID="${SHOP_ID:-1}"
RESULT_DIR="jmeter/results/dual-instance-$(date +%Y%m%d-%H%M%S)"

if [[ ! -x "$JMETER_BIN" ]]; then
  echo "找不到可执行的 JMeter：$JMETER_BIN" >&2
  exit 1
fi

mkdir -p "$RESULT_DIR"

"$JMETER_BIN" -n -t jmeter/shop-cache-singleflight.jmx \
  -l "$RESULT_DIR/instance-8081.jtl" \
  -e -o "$RESULT_DIR/instance-8081-report" \
  -Jport=8081 -Jthreads="$THREADS_PER_INSTANCE" -JrampUpSeconds=1 -JshopId="$SHOP_ID" &
first_pid=$!

"$JMETER_BIN" -n -t jmeter/shop-cache-singleflight.jmx \
  -l "$RESULT_DIR/instance-8082.jtl" \
  -e -o "$RESULT_DIR/instance-8082-report" \
  -Jport=8082 -Jthreads="$THREADS_PER_INSTANCE" -JrampUpSeconds=1 -JshopId="$SHOP_ID" &
second_pid=$!

wait "$first_pid"
wait "$second_pid"

echo "双实例结果目录：$RESULT_DIR"
