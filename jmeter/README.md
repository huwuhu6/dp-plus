# AI 链路压测

`ai-decision-baseline.jmx` 压测 `POST /ai/decisions` 的完整决策路径，默认使用低并发，避免把本地聊天模型 API 误当成普通 CRUD 服务压垮。

在 PowerShell 中执行：

```powershell
& 'D:\03_software\02_安装路径\02_开发工具\apache-jmeter-5.6.3\apache-jmeter-5.6.3\bin\jmeter.bat' -n `
  -t jmeter\ai-decision-baseline.jmx `
  -l jmeter\results\ai-decision-baseline.jtl `
  -e -o jmeter\results\ai-decision-baseline-report `
  -Jthreads=2 -Jloops=2 -JrampUpSeconds=5
```

提高并发前先确认模型供应商配额和本地 Milvus、MySQL、Redis 都可用。每次策略或模型改动后，应保留 `.jtl`、HTML 报告和对应的评测运行 ID，用于结合错误率、P95 延迟、Token 和质量指标判断是否回归。

已验证的第二档本地受控并发为 `-Jthreads=4 -Jloops=2 -JrampUpSeconds=10`：共 8 次真实请求，错误率 `0%`、平均 `4339ms`、P95 `5020ms`。这不是供应商配额下的最大吞吐量结论，而是本地端到端稳定性基线。

## 套餐券履约并发争抢

`voucher-fulfillment-verify-race.jmx` 用于验证“同一张套餐券只能被成功核销一次”。压测前先通过 `POST /voucher-fulfillment/packages` 创建一张未使用测试券，并向 Redis 写入测试 token；不要使用已有真实券码。脚本的同步定时器会让所有线程同时提交独立请求号的核销请求。

```bash
~/Downloads/apache-jmeter-5.6.3/bin/jmeter -n \
  -t jmeter/voucher-fulfillment-verify-race.jmx \
  -l jmeter/results/voucher-fulfillment-verify-race.jtl \
  -JcertificateNo=VC替换为新建测试券码 \
  -Jtoken=测试登录token \
  -Jthreads=20
```

JMeter 只断言响应结构，不把业务拒绝标为 HTTP 错误。运行后必须查询 `tb_voucher_fulfillment_audit` 和 `tb_voucher_certificate`：20 个不同请求号应产生恰好 `1` 条 `VERIFY/SUCCESS`、`19` 条 `VERIFY/REJECTED`，券最终为 `USED`。这才是 CAS 正确性的结论；延迟、吞吐和 HTTP 200 仅是辅助指标。

`run-voucher-fulfillment-dual-instance.sh` 是双 JVM 版本：应用分别启动在 `8083`、`8084` 并共享 Redis/MySQL 后，向两端各发送 10 个同券核销请求。它用于证明 CAS 的跨实例正确性，不是吞吐压测：

```bash
bash jmeter/run-voucher-fulfillment-dual-instance.sh VC替换为新建测试券码 测试登录token
```

`run-voucher-package-aggregation-dual-instance.sh` 覆盖同一套餐内不同券并发履约时的订单状态聚合。创建一笔恰好两张券的套餐，应用启动在 `8083`、`8084` 后执行：

```bash
bash jmeter/run-voucher-package-aggregation-dual-instance.sh 券码A 券码B 测试登录token
```

运行后必须核对两张券均为 `USED`，父套餐订单也为 `USED`。这验证的是跨 JVM 的聚合状态正确性，不能用两次 HTTP 200 替代数据库核验。

## 套餐券原子计数器并发基线

`voucher-package-order-lock-benchmark.jmx` 与 `run-voucher-package-lock-benchmark.sh` 复用套餐券接口、Redis 登录态和本机 MySQL。每个测试用户各创建一笔 **10 张同类券** 的套餐订单；每个线程只绑定一张不同券，因此不包含同券 CAS 竞争。脚本名称保留，便于与首次“订单锁 + 全量券锁”基线直接对比；当前运行时实现为单券 CAS 与订单原子计数器。

默认依次运行 `5/10/20/50` 并发：`5`、`10` 使用 1 笔订单，`20` 使用 2 笔订单，`50` 使用 5 笔订单。每笔订单内最多有 10 个并发履约请求，避免构造单笔 50 张券的非典型数据。5 并发场景只核销其中 5 张，因此预期订单为 `PARTIALLY_USED`；其余场景每笔测试订单均应为 `USED`。

先启动应用。默认使用 `8083` 单 JVM：

```bash
bash jmeter/run-voucher-package-lock-benchmark.sh
```

若已启动 `8083`、`8084` 两个实例，下面的方式会将线程轮流打到两个 JVM，用于补充验证结果不依赖单 JVM 内存锁：

```bash
BENCHMARK_PORTS=8083,8084 bash jmeter/run-voucher-package-lock-benchmark.sh
```

脚本在 `jmeter/results/` 下创建按时间戳隔离的结果目录，该目录受 `.gitignore` 保护。每一档都保存 JMeter `.jtl`、线程专属券/用户 CSV、订单预期、数据库校验和汇总 `metrics.csv`；若成功数、P95/P99 源数据，或 MySQL 的订单持久化计数、券状态、审计去重和订单状态不一致，脚本会失败退出。该基线用于比较有限券数、有限同订单并发下的并发控制开销，不用于宣称全局性能最优或系统容量上限。
