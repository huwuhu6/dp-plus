# AI 消费决策 Agent——面试资料入口

> 这份文件是 `docs/interview-prep` 分支唯一的面试复习入口。
>
> 原则：**不要从架构名词开始背。先看简历写了什么，再看面试官为什么会追、会怎么追。**

## 一、这套资料怎么用

这个项目很大，但面试时不应该把所有模块都主动暴露出来。简历每写一个技术点，就等于主动给面试官一个追问入口。因此当前只建议把项目拆成 3 条可独立深挖的主线：

1. 多轮 Working Memory / 状态一致性；
2. MySQL + Milvus + Rerank 混合检索；
3. Agent Trajectory Evaluation。

每条线对应简历上的一个 bullet。目标不是“知道整个项目所有类”，而是：**简历主动写出的每一条线，都能承受 10 分钟左右连续追问。**

复习顺序固定为：

```text
简历怎么写
→ 面试官第一眼会抓什么词
→ 第一层朴素问题
→ 回答里又暴露什么概念
→ 第二层追问
→ 真实 Bad Case
→ 为什么第一版不行
→ 当前实现
→ Trade-off / 当前不能吹什么
```

如果某个追问答不上来，再去开发记录、待修清单或 archive 里的旧专题文档查细节。不要反过来从旧专题文档顺序阅读。

---

## 二、三条主线

### 主线 1：多轮 Working Memory / 状态一致性

对应资料：`01_多轮状态与WorkingMemory——简历驱动追问链.md`

这条线主要证明：你不是“给 LLM 多塞聊天历史”，而是在解决多轮业务状态的确定性、状态写权限、历史任务切换和并发一致性。

典型追问路径：

```text
为什么不用 Chat History
→ Working Memory 存什么
→ 槽位怎么更新
→ 模型抽到字段就直接写吗
→ Mention ≠ Mutation
→ 为什么需要 Task
→ 历史推荐怎么引用
→ 同一 Turn 为什么只能有一份 Snapshot
→ OCC
→ 慢 Tool 的旧结果怎么办
```

Location、RecommendationBatch、TurnPlan 等不单独作为简历 bullet，它们优先作为这条主线被追深后的真实案例。

### 主线 2：MySQL + Milvus + Rerank 混合检索

对应资料：`02_混合检索与Rerank——简历驱动追问链.md`

这条线主要证明：你知道结构化约束、向量语义相关性和最终业务排序分别解决什么问题，而且能按当前真实代码讲，不把所有 hard filter 都吹成 SQL 下推。

典型追问路径：

```text
为什么 MySQL 和 Milvus 都要
→ 为什么不能纯向量
→ hard filter 在哪一层
→ Milvus 存什么
→ Profile / Review 为什么分开
→ 为什么还需要 Rerank
→ 阈值 / TopK / 权重怎么来的
→ Recall / MRR 怎么看
→ 数据变化以后向量怎么增量同步
```

### 主线 3：Agent Trajectory Evaluation

对应资料：`03_Agent评测——简历驱动追问链.md`

这条线主要证明：你不只是“跑几个 Case 看最终回复”，而是知道有状态 Agent 为什么需要 trajectory-level contract、如何设计 Ground Truth、怎么用 Holdout 防 case chasing。

典型追问路径：

```text
为什么不能只看最终回答
→ 一条 Case 到底检查什么
→ Ground Truth 怎么设计
→ 为什么优先 deterministic grader
→ Robustness / Conversation / Holdout 分工
→ Recall / MRR 和 Agent trajectory 指标什么关系
→ Complete 为什么不高
→ 为什么不继续修到 100%
```

---

## 三、哪些东西不要主动写进简历

以下内容不是没价值，而是更适合作为“被追问以后拿出来”的储备：

```text
Canonical Turn Semantics
TurnCommandSet / TurnPlan
RecommendationBatch
Location Contract
Administrative Resolver / POI Grounding
Decision Context Query
Command Projection
Vector Sync Task
SSE
LangGraph / Multi-Agent 取舍
Event Sourcing 取舍
```

如果把这些全部平铺到简历里，会制造过多攻击面，而且每个点都只能讲浅。

---

## 四、当前事实底稿

面试资料只负责“怎么讲”。真实实现与历史演进仍以以下文件为事实底稿：

- `AI消费决策Agent开发记录.md`：真实问题、方案演进、评测结果；
- `待修清单.md`：当前仍存在的工程债和不能夸大的地方；
- 当前 `main` 代码：最终 Code Truth。

旧的 Working Memory v1/v2、Location、Turn Semantics、Provenance 等专题资料属于历史阶段文档，不再作为当前学习入口。