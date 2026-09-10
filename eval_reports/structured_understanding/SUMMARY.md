# Structured Understanding Evaluation Archive

这些文件是评测 Run 的只读快照，不依赖数据库长期保留历史结果。原始逐轮 trace 位于对应 `run-<id>.json`。

| Run | Dataset | Mode | Cases | Complete | Route | Tool | Final | Locality | Trace |
|---:|---|---|---:|---:|---:|---:|---:|---:|---|
| 147 | conversation-robustness-v1 | off | 48 | 21 | 44 | 47 | 39 | 48 | 48/48 |
| 148 | conversation-v1 | off | 40 | 27 | 36 | 31 | 40 | 40 | 40/40 |
| 149 | conversation-holdout-v1 | off | 16 | 7 | 13 | 14 | 12 | 16 | 16/16 |
| 150 | conversation-robustness-v1 | shadow | 48 | 21 | 44 | 47 | 38 | 48 | 48/48 |
| 151 | conversation-v1 | shadow | 40 | 29 | 38 | 33 | 40 | 40 | 40/40 |
| 152 | conversation-holdout-v1 | shadow | 16 | 7 | 13 | 14 | 12 | 16 | 16/16 |
| 153 | conversation-v1 | shadow | 40 | 28 | 37 | 32 | 40 | 40 | 40/40 |
| 154 | conversation-robustness-v1 | shadow | 48 | 21 | 44 | 47 | 39 | 48 | 48/48 |

Unavailable fields are explicitly marked in each JSON snapshot; no value is inferred from a missing API/DB field.
