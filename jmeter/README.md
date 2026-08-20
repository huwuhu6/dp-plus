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
