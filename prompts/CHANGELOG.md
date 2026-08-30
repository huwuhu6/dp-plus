# Prompt 变更记录

所有 Prompt 版本变更在此记录，详细内容见对应子目录。

## 目录

| 日期 | Prompt | 变更类型 | 摘要 |
|------|--------|---------|------|
| 2026-08-30 | [constraint-extractor/v1](constraint-extractor/v1.md) | 基线提取 | 当前生产环境 Prompt，首次纳入版本管理 |
| 2026-08-30 | [constraint-extractor/v2](constraint-extractor/v2.md) | Few-shot 微调 | 修复实体边界误判：菜品名中的地名不视为搜索城市 |

## 变更说明

### 2026-08-30 constraint-extractor v1 → v2

- **问题**: "帮我找重庆鸡公煲" → targetCity="重庆"（假阳性）
- **根因**: 模型将菜品名"重庆鸡公煲"中的"重庆"识别为搜索城市，未理解"重庆鸡公煲"是实体名称而非"重庆的鸡公煲"
- **方案**: 系统提示词增加实体边界规则 + 4 组 few-shot 正反例
- **验证**: 18 基线查询 + 5 holdout，对比见开发记录