# DineWise V2：统一评测与优化对比

检查日期：2026-10-15。评测代码/main SHA：`e98c55c90d619ce5d091c25f694404638178a8cd`。本轮只整理与运行评测，不修改 Production、Prompt、参数、数据集或标准答案，不读取或运行 Holdout。

## 1. 主成绩单与边界

| 指标 | V2 Main（主指标） | V2 Semantic Main（独立指标） |
|---|---:|---:|
| 数据集 | conversation-v2-main-v2 | conversation-v2-semantic-main-v1 |
| 正式 Run ID | 201 | 202 |
| 实际执行用例 / 轮次 | 24 / 48 | 10 / 20 |
| 官方 Case Pass | **24/24，100%** | **8/10，80%** |
| 失败用例 | 0 | 2 |
| 含执行异常的用例 / 异常轮次 | 0 / 0 | 2 / 2 |
| 断言失败条数 | 0 | 3 |
| 评测基础设施异常 | 0 | 0 |
| 逐轮 trace 中模型成功 / 失败调用 | 53 / 0 | 23 / 0 |
| 最终状态 | COMPLETED | COMPLETED_WITH_ERRORS |

Semantic 的两项异常已经包含在两项失败中，不能重复相加。调用数包含 Production 自身的有界修复；每套正式评测仅提交一次，没有挑选重跑结果。不合并两套分数为“Agent 总准确率”。

**24/24 只表示通过冻结的现有断言。Main 条件分支用例仍暴露业务语义错误，但未被其现有预期覆盖（见第 5 节）。不能据此宣称业务完全正确或完成生产验收。**

运行模型 `dashscope / deepseek-v4-flash-0731`，temperature=0.1，timeout=20000ms；沿用当前默认配置，没有采用此前受控实验的 60000ms 覆盖。现有重试上限3次，初始500ms、上限5000ms、总预算90000ms。没有改模型参数或重试实现。

数据集原文件 SHA-256：

- Main：`98cf2b591f59875363c90189e7ff077454bf108a07aa6c8aa089c3eb7a43adc0`
- Semantic：`ca5f9e525b2d427d13941cd7bf851c5da2efa21c83bc2938da42807153415ce3`

实际 loader 返回的用户输入、预期及跨轮断言均与原 JSONL 一致。后端代码无工作区差异；已有 `nginx-1.18.0` 子模块脏修改未动，实际 git describe 为 `e98c55c-dirty`。原始 metadata 的 dirtyWorkspace=false 仅来自主仓常规状态检查，不能代表子模块干净；以补充的 `provenance-verification.json` 为准。

## 2. 统一现有评测的用途

| 层次 | 既有入口 / 数据 | 用途与统计口径 |
|---|---|---|
| 主 Benchmark | `AiConversationEvaluationService.submitDatasetCases("conversation-v2-main-v2", Set.of())`；资源目录 eval/datasets 下24条 JSONL | 多轮状态、身份、反馈、硬约束及受支持能力的固定契约回归，主成绩单保持24条 |
| 语义 Benchmark | 同服务，显式版本 `conversation-v2-semantic-main-v1`，10条 JSONL | 语义投影与部分指令/条件契约；独立列分，不是所有槽位的完整准确率 |
| 检索评测 | `AiEvaluationService.runActiveCases()`，`POST /ai/evaluations/runs`，seed-v2 | Recall@K、MRR、约束与证据，不等同对话 Case Pass |
| 检索消融 | `experiments/retrieval-ablation/retrieval_ablation_experiment.py` 与已有快照/报告 | 固定数据和标签下比较排序组件贡献，不是当前真实模型多轮成绩 |
| 代码回归 | Maven 单元/集成测试 | 确定性正反例与工程回归，不计作模型业务通过率 |
| 16场景/93项 | 冻结 v1.1 业务规范；现有 Pilot / Controlled Harness 与证据 | 较细业务验收、失败定位与事实边界，作为补充诊断，不并入24条分母 |

正式 HTTP 入口为 `POST /ai/evaluations/conversation-runs/subset`，显式传 datasetVersion；结果与诊断分别 GET `conversation-runs/{runId}`、`{runId}/diagnostics`。不要依赖默认活动版本推断运行了哪套 Main。

本轮忽略目录中的一次性 CLI 仅启动真实 Spring 应用并调用上述正式服务，没有 Mock 或另一套评分实现。启动时关闭 Flyway、画像重建及向量同步后台写任务，保留真实 Chat、数据库、模型与检索依赖。运行创建正常隔离 eval 会话及评测记录，没有修改商户资料、画像或索引。

源码判定依据：

- `ConversationEvaluationDatasetLoader` 对 conversation-v2-* 强制版本化 JSONL，经 `V2DatasetValidator` 校验，不回退历史数据库用例。
- `AiConversationEvaluationService` 逐轮走 Chat 主链并读取快照；`expectedV2Outcomes`、`expectedTurnStates`、`expectedRelations` 的不匹配参与最终失败判断。`hasFailure`（约1059行）同时考虑状态、商户、记忆、恢复、去重、V2断言与执行异常。
- 迁移后 route/tool 名称是旧诊断项，不应将其空预期默认匹配解释成已验证某工具对象，也不把 route 不匹配直接当 Case 失败。
- `VerificationStatus` 区分未执行、不适用与通过；没有可验证结果不证明事实正确。用例缺少某类可选断言，只能说该类未覆盖。

本轮不修改评分规则，不新增平台，也不为补齐覆盖新增测试。

## 3. 16场景与原 Main 的覆盖关系

分类允许并存：A=原24条已覆盖的能力；B=新增独立能力；C=外部事实依赖；D=重复回归或辅助诊断。覆盖能力不代表原 Main 已逐项验证该自然语言场景。

| 场景 | 分类 | 与原24条的关系及边界 |
|---|---|---|
| S01 距离收紧 | A/C | HARD_DISTANCE、CRITERIA_MULTI_PATCH、LOCATION 系列覆盖距离与锚点；实际距离基准、楼下位置仍需事实 |
| S02 忌辣+商户问价 | B/C | 多请求与价格主题、限制同轮保存超出简单状态断言；需商户价格口径/辣度证据 |
| S03 单人酸菜鱼 | A/B/C | 条件继承可复用；菜品、一人份及快餐形态是更细目标，需商品事实 |
| S04 预算下调 | A/C/D | CRITERIA_OVERWRITE、RELATIVE及HARD_BUDGET已有核心覆盖；三四十容差与价格口径需独立判据 |
| S05 正餐转下午茶 | A/B/C | 条件替换/继承机制可复用；咖啡与蛋糕组合、当前营业需菜单和时间证据 |
| S06 无骨商品 | B/C | 原24条没有商品属性与 UNKNOWN 完整回复契约；通用详情不能证明有无无骨产品 |
| S08 冷面 OR 寿司 | B/D | 原24条 CLEAR主要检验预算，MULTI_PATCH检验预算/距离；没有菜系替换+OR+旧菜系清除的完整覆盖 |
| S09 整批不满意 | A/C/D | FEEDBACK_BATCH_NEGATIVE、ALTERNATIVES_DISJOINT覆盖批次反馈与不重复；不能外推永久拉黑或楼层事实 |
| S10 选择后问楼层 | B/C | 原24条反馈不等同“选店+事实回答”组合；楼层必须核对具体分店 |
| S13 指代不唯一 | A/B/D | REFERENCE_UNRESOLVED_ORDINAL覆盖不可定位拒绝；“哪家”澄清对象及不猜测仍需语义/回复证据 |
| C03 同伴饮食冲突 | B/C | 多条件机制可复用，但食材禁忌、辣度冲突不等于菜系排除；菜单安全不可由店名证明 |
| C04 停车后换店 | A/C/D | 拒绝、替换、不重复可复用；停车与聚餐事实、半径来源是额外证据，不应另算一套准确率 |
| C06 旧反馈+新任务 | B/C | TASK_RESTORE系列验证切换/恢复；不包含同轮旧反馈与新任务动作分别归属、原子隔离；另有站点/接待能力事实依赖 |
| C10 单人烧鹅饭 | A/B/C | 条件保留可复用；烧鹅兴趣与一人快餐形态不能泛化成任意快餐，需具体商品证据 |
| C15 历史商户引用 | B/C | REFERENCE_HISTORY_RESTORE先恢复任务后引用可见序号；不等于不恢复条件、跨批次名称查历史商户；招牌菜等另需事实 |
| C23 条件选店 | B/C | 24条 CONDITIONAL_SEMANTICS仅覆盖搜索条件补丁数量/距离，不包含事实guard与选择effect；不辣必须是同店合适主餐证据 |

建议补充回归集合最多保留 **S08、C06、C15、C23**，分别补齐 OR替换、跨任务动作、历史批次身份、事实guard选择。直接复用 `V2ControlledPilotTest`、`V2RealModelPilotTest`、`V2ControlledFourRealModelTest` 及既有 Fixture/记录；本轮不重跑、不新建集合或改评分。其余保留为业务验收材料、现有组件测试或诊断证据，不删除冻结要求。

S02/S06 的既有 `MerchantFactRegressionTest` 等组件回归继续保留，不再复制为新的场景 Benchmark。16场景/93项不进入24条主分母；此前受控4/10也不表示整个 Agent 准确率。

## 4. 历史基线与优化对比

本轮读取正常版本的数据库评测记录、保存的用例结果及 Git 历史，未查询 Holdout 业务内容。

| 历史记录 | 原始成绩 | 与本次是否严格可比 |
|---|---|---|
| Run167，conversation-v2-main-v1 | Case Pass **22/41**；V2Outcome匹配26/41 | 否：41条旧版，不得与24条比较提升 |
| Main-v2 Run168/169/171/172/174/178 | 依次13/24、14/24、19/24、20/24、20/24、20/24 | 否：早期数据/代码/模型及评分演进，不能挑最低一次当基线 |
| Main-v2 Run188/189/190/191/192/194 | 依次24/24、24/24、22/24、24/24、23/24、23/24 | 否：同名版本不足以保证代码与运行条件相同 |
| Main-v2 Run196/197/198/199/200 | 依次23/24、23/24、24/24、24/24、23/24 | 否：不能挑最后23/24算当前提升，历史已有24/24 |
| Semantic Run163/164/165 | 9/10、10/10、10/10 | 否：不能直接把当前8/10定量认作同条件退化 |

数据库中以上为17次完整 Main-v2 运行；其他仅1～4条的子集不当作24条基线。Run167/163～165记录 git=`f3ba8d5-dirty`；Main-v2早期至近期包含 `3f0e448-dirty`、`890a2c8-dirty`、`7119a0a-dirty`、`812b085-dirty` 等。它们只能定位提交基底，不能还原当时未提交的实际后端。

历史模型记录 `deepseek-v4-flash`，本次为 `deepseek-v4-flash-0731`，没有证据可认定同一实际模型。Git核对发现部分后期 JSONL 与当前相同，但评测服务/校验器已变化；早期 Main JSONL 本身也有差异。历史缺少完整参数、商户世界快照及可复原的干净 Production SHA。

**结论：没有严格可比的整体优化前基线，不计算整体提升百分比，也不能可靠列出同条件的“修复成功项/新增失败项”差集。** 当前24/24、8/10是从本轮开始可追溯的成绩。

可保留的局部前后证据：

| 同话语、同Fixture | 修复前实际行为 | 修复后实际行为 |
|---|---|---|
| S02“上火了不能吃辣，椰子鸡大概多少钱？” | GENERAL，回答不支持开放知识问答 | PRICE目标22，回答人均40及价格口径，同时保存忌辣、不编造辣度 |
| S06“第二家韩国炸鸡，他们家有无骨的吗？” | DETAIL目标22，只答基础资料，遗漏无骨问题 | PRODUCT目标22，保留商品属性，缺菜单时明确无法确认，不等于没有 |
| C23“第二家辣不？不辣就去。” | 同店主餐事实成立、POST选22，但回复遗漏选店 | 同事实/选择effect，同时回复事实和已按意愿选定；不声称订座下单 |

前目录 `target/eval-pilot/real-controlled-four-20261014-once/`，代码 `27cb6c9307499ffa7a6f68ed69e1ab7d4a28d714`；后目录 `real-merchant-fact-fix-20261014-once/`，代码 `f04f94ea47240664149b6e578c7ac5bc75d57def`。已实际核对三项原话及 Fixture JSON 相同；均为同模型0731、60000ms的受控实验，外部事实TEST_ONLY。这些是局部行为对照，不是全量提升或真实首轮端到端成绩，也不是本轮新增运行。

另有可比但独立的**历史检索消融**：`ablation_report_20260831_191253.json`，ablation-v1，35条（31条活跃排序、4条放宽），112条GT商户。同快照 V_Base→V_Sem：Recall@3 0.7291→0.7614（+3.23个百分点），NDCG@3 0.9355→0.9558，Top1均0.9355，约束违反均0。它只支持当时语义加分组件贡献，不能冒充本轮 V2 优化幅度。

## 5. 主要失败及统计风险

### 5.1 条件分支范围错误与 Main 断言盲区

Main `MAIN_V2_CONDITIONAL_SEMANTICS` 与 Semantic `SEM_CONDITIONAL` 的同一句输入：“如果3公里内没有藏式火锅，就改找烧烤”。两次 FIRST 都正确表达主条件藏式火锅、3km、EMPTY后改烧烤。

但 `SemanticInterpreter.requiresCuisineCoverage`（约549～568行）结合 `CuisineCanonicalizer.requestedCategories`（约228行）把条件从句中的“改找烧烤”也要求进入根菜系，误触发 `CUISINE_COVERAGE_REQUIRED`。这不是 FIRST 没理解备用条件。

- Main 定向提取生成根 `cuisineAnyOf=[藏式火锅,烧烤]`，最终通过验证并搜索。备用项被提前纳入主条件，已出现错误语义；冻结预期仅检查 conditionalChangeCount=1、distance=3，故官方仍判通过。
- Semantic 定向提取同时输出单值 `cuisine=藏式火锅` 和 OR数组，parser报单菜系与OR互斥，整轮失败、快照不可用。

最早问题是 Production 覆盖校验的条件作用域，之后传播为错误OR或非法修复；Main另存在断言覆盖不足。两类问题需分开，本轮都不修，不改官方得分。

### 5.2 无依据字段及修复失败

`SEM_CLEAR` 首轮“找人均100以内的火锅”：FIRST错误生成 distanceKm=100、partySize=1且遗漏预算，触发距离来源错误；REPAIR仍有无依据人数并新增不辣，来源校验拒绝。首轮零提交；后轮“预算不限”虽然解析清除预算，但没有已建立上下文而澄清，投影出现 PATH_ABSENT。

最早失败是模型单位/字段生成与修复来源问题，不能由后轮现象直接认定清除预算 reducer 坏了。两个失败用例均为正常模型响应后的语义契约失败；没有模型传输失败或本轮评测器运行异常。

### 5.3 V2模型计量汇总漏记

两次 Run/Case 表 modelCallCount、token 等聚合值均0，但逐轮 `modelCalls` trace实际为53和23次成功调用。`AiConversationEvaluationService.populateModelMetrics`（约1319行）依赖旧 `decisionSessionIds→AiDecisionMetric`，V2缺少该旧ID，导致聚合漏记；`OpenAiCompatibleClient.observationPurpose` 还把用途记为OTHER，不能声称取得可靠FIRST/REPAIR用途汇总。

报告使用逐轮 trace 重算的独立旁证，不改数据库成绩或实现；聚合0不能解释成无模型参与。此为现有观测缺陷，不是这次两项业务失败的根因。Run的errorRate也不能当成模型服务错误率。

### 5.4 事实覆盖与评分覆盖仍有限

原24条不是菜单、营业、楼层、接待能力或所有明确槽位的完整业务验收。当前库980商户/975画像；此前只读审计福州330家、画像330份，面食/米线/酸菜鱼/炸鸡等指定类别在Production画像匹配为0；评论线索不能替代当前菜单事实。

已有真实首轮“福州市三坊七巷附近找面馆，人均50以内”修复后证据为真实SQL4→菜系0→最终0，条件没有删除，Milvus未调用。这支持正确保留条件和如实零结果，不证明数据库不存在该餐品。相关数据/证据缺口与模型语义错误分开记录，本轮不改数据、不重跑首轮。

## 6. 已验证能力与仍未验证能力

本轮正式断言支持：预算覆盖/清除/相对更新、多条件更新、任务恢复与放弃、可见序号身份、反馈批次与候选去重、设备/缺失/歧义定位边界、预算与距离硬约束，以及不支持验证时不伪造成功。通过的是指定固定输入及其断言，没有证明任意自然语言泛化。

历史受控证据另支持 C06 旧反馈/独立新任务原子隔离、C15跨批次工具目标22且不恢复旧条件、S02/S06商户事实主题保留、C23 guard实际成立后选择与回复组合；这些不并入本轮24条分母。

仍未验证或不能宣称完成：完整93项验收；任意复合自然语言的来源/覆盖正确率；真实菜单/楼层/营业/接待能力；真实首轮稳定生成场景要求的列表；Milvus在这些无候选场景中的端到端收益；模型多次运行稳定性。此前530 passed、8 skipped仅为代码回归，本轮文档任务没有重跑 Maven，不称其为新增验证或模型成绩。

## 7. 证据与复查位置

本轮原始输出保留于忽略目录 `target/eval-unified/formal-main-semantic-20261015-once/`：

- 两套 `*-result.json`：正式Run及Case结果、逐轮输入/语义/模型调用/状态/工具/回复；`*-diagnostics.json`：失败诊断；`*-loaded-cases.json`：实际加载的冻结输入与断言。
- `verified-summary.json`：从正式结果重算执行数量、异常和模型trace计数；`provenance-verification.json`：后端无差异、子模块状态、JSONL与加载预期一致的补充核查。
- `metadata.json`、STARTED/COMPLETED：模型配置与执行边界；`history-runs.json`、`history-167-results.json`、`historical-content-audit.json`、`retrieval-history.json`：历史数据库/Git核对证据。

进程日志 `target/eval-unified/formal-main-semantic-20261015-once.log`；执行现有应用/服务的一次性启动文件在同级忽略目录，不进入产品或Maven测试集。生成物与配置秘密均不提交；数据库保留正式Run201/202，Git保留本报告及开发记录，原始target证据需要在清理构建目录前自行归档。

本轮以此收口：24条Main保持唯一主Benchmark，10条语义Main独立列示，检索消融与自然语言场景各自回答不同问题；无严格整体前后提升结论，不继续修复发现的问题。
