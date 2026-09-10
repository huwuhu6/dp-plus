# Structured Understanding 评测归档

这里保存 Structured Understanding 实验的只读快照：

- `run-<id>.json`：单个 Run 的完整 JSON，包含聚合指标、Case Result、逐轮 `turnTrace`、`modelCalls`、`structuredUnderstanding`、validation/fallback 和 stage trace。
- `manifest.jsonl`：每个 Run 一行的机器可读索引。
- `SUMMARY.md`：便于人工快速比较的指标表。

评测完成并处于 `COMPLETED` 或 `COMPLETED_WITH_ERRORS` 后，在应用可读到该 Run 时执行：

```powershell
python tools/archive_conversation_eval.py --run-id 153 --mode active
```

脚本只调用 GET 接口，不会提交或重跑评测。`--mode` 必须填写本次运行实际使用的 `off`、`shadow` 或 `active`。应用不可用时，历史导出也支持只读 MySQL：

```powershell
$env:EVAL_ARCHIVE_DB_PASSWORD = '<本地数据库密码>'
python tools/archive_conversation_eval.py --source mysql --run-id 153 --mode active
```

无法从 Run/API/数据库取得的字段会显式标记为 `unavailable`，不会推测补齐。脚本会过滤 API key、Authorization、Cookie、密码和 Bearer 凭据；token 数量等观测指标会保留。
