# AI 链路压测

`ai-decision-baseline.jmx` 压测 `POST /ai/decisions` 的完整决策路径，默认使用低并发，避免把本地聊天模型 API 误当成普通 CRUD 服务压垮。

商铺热点缓存的受控并发对比见 [商铺缓存 L1 与并发合并记录](../doc/商铺缓存L1与并发合并可靠性开发记录.md)。脚本为 `shop-cache-singleflight.jmx`，结果必须同时核对 `/internal/cache/shop/stats` 的 L2 回源次数与 HTTP 延迟，不能只看 HTTP 200。

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
# 商铺缓存可靠性压测

`shop-cache-singleflight.jmx` 用于单 JVM 同 key 并发合并验证。传入 `-Jport=8081` 或 `-Jport=8082` 可指定目标实例。

`run-shop-cache-dual-instance.sh` 会并行向 `8081`、`8082` 各运行一组 100 线程的同 key 压测。若 Redis L2 为空，它同时也会验证双 JVM 的首次填充竞争；此时不要预期 MySQL 回源为零。两个应用实例均启动后执行：

```bash
chmod +x jmeter/run-shop-cache-dual-instance.sh
jmeter/run-shop-cache-dual-instance.sh
```

`run-shop-cache-consistency-window.sh` 用于测量多 JVM 下 L1 的陈旧窗口。它会通过真实 `PUT /shop` 临时修改 `tb_shop.name`，默认拒绝执行；必须显式传入允许数据变更与原始名称，脚本结束前会恢复原值。

```bash
ALLOW_DATA_MUTATION=true \
ORIGINAL_NAME='103茶餐厅' \
bash jmeter/run-shop-cache-consistency-window.sh
```

该脚本关注的是“另一个 JVM 的本地 L1 多久能收敛”，不是吞吐压测。执行前要确认 `8081`、`8082` 两个应用实例都已启动，并且二者连接同一个 Redis 与 MySQL。
