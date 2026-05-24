# BACKEND_CLEANUP_AUDIT.md

> 第二轮清洗 — **后端语义级**审计。本文件按 `clean.txt`(2026-05-24)§1-§5 输出。
> **本阶段只审计、不删代码、不动业务行为**。结论汇总见末尾 §7,推荐删除项见 §8。

---

## 0. 审计基线

| 项 | 值 |
|---|---|
| 仓库 | `D:\desktop\stu\software\src_24281231` |
| 分支 | `claude` |
| 最近 5 个 commit | `b0ca6ed` 第一轮 CLEANUP_REPORT → `63327b2` PR-CLEAN-4 → `44b535e` audit/plan → `68e6864` PR-CLEAN-1 → `9c984fa` RFC-012 baseline |
| 工作树 | 仅 `.vscode/settings.json`(预存 IDE 改动,与本轮无关) |
| 后端 main `.java` | **113 个**(controller 11 / dto 41 / engine 18 / model 9 / service 31 / config 1 / 启动 1,加上 SimulationApiSupport / ReportResponseBuilder 等支持类) |
| 后端 test `.java` | **59 个**,其中 `tools/CuratedSamplesGenerator.java` 是工具类不计入正常 test |
| 前端 `.js/.jsx` | 41 个(其中测试 12 个) |
| 上一轮基线测试通过数 | mvn 468/0/0,npm 62/0/0 |

> 第一轮 `CLEANUP_REPORT.md` §5 把 sync `/optimize` 路径与 `FULL_REPORTS_DEBUG / WORKLOAD_ROUTING / HYBRID_OVERFLOW` 列为 P3 暂缓。本轮按 `clean.txt` 重新审视这些项,作出建议(不执行)。

---

## 1. 当前最新功能边界(`clean.txt` §1 18 项)

每行字段:**前端调用** / **Controller 暴露** / **测试覆盖** / **文档提及** / **是否最新版本必需** / **建议**

| # | 模块 | 前端 | Controller | 测试 | 文档 | 最新? | 建议 |
|---|---|---|---|---|---|---|---|
| 1 | 基础仿真 `/api/simulation/run` | ✅ `runSimulation` (App.async.test.jsx 也 mock) | `SimulationController.run` | `SimulationApiIntegrationTest` 多用例 | `API.md` §run / `README.md` / `ARCHITECTURE.md` | ✅ | **保留** |
| 2 | 异步仿真 `/api/simulation/run/async` | ✅ `runSimulationAsync` | `SimulationController.runAsync` | `SimulationApiIntegrationTest` + `SimulationTaskServiceLifecycleTest` | ✅ | ✅ | **保留** |
| 3 | report 查询 `/report/{id}` `/report/latest` `/report/{id}/timeline` `/report/{id}/history` `/report/{id}/csv` `/report/list` | ✅ `getReportById` `loadLatestReport` `loadReportHistory` `csvExportUrl` | `SimulationReportController` 6 个 endpoint | `SimulationApiIntegrationTest` 多用例 | ✅ | ✅ | **保留**(timeline endpoint 前端**未直接调**,但 history 走 `/history`,csv 走 `csvExportUrl`,latest/list 暂保留) |
| 4 | scenario preset `/scenarios` `/scenarios/run` | ✅ `loadScenarioCatalog` `runScenarioBatch` (App.jsx 启动加载) | `SimulationScenarioController` | `SimulationApiIntegrationTest` `ScenarioRunServiceContractTest` | ✅ | ✅ | **保留** |
| 5 | PREFERENCE_AWARE 队列模型 | ✅ InputPage 选项 | `SimulationEngine.applyQueueChoiceModel` | `QueueChoiceModelPr9bTest` `Pr9cIntegrationTest` `Pr9dTest` `Pr9eTest` `WindowAttractivenessSamplerTest` `WindowSelectionPolicyTakeawayIntentTest` `WindowBalancingTest` 等 | `API.md` `USER_GUIDE.md` `METRICS.md` | ✅ | **保留**(`clean.txt` §8.3 红线) |
| 6 | `window_choice_metrics` (PR-9D) | ✅ `WindowChoiceMetricsCard` 组件 + 测试 | 由 `SimulationRunService` 注入到 summary | `WindowChoiceMetricsBuilderTest` `Pr9dTest` | `METRICS.md` | ✅ | **保留** |
| 7 | `wait_experience_proxy_metrics` (RFC-011) | ❌ **前端 grep 0 命中**(无组件读取) | 由 `SimulationRunService` 注入到 summary | `WaitExperienceProxyCalculatorTest` (11) `SimulationSummaryRfc011IntegrationTest` (3) | `METRICS.md` `improvement_plan_2026-05-21.md` | ⚠️ 离线/JSON-only | **保留**(刚 R4 落地,JSON 字段已存在,前端接入未排期但是是已宣布的指标) |
| 8 | `fairness_metrics` (RFC-011) | ❌ **前端 grep 0 命中** | 同上 | `FairnessCalculatorTest` (8) `SimulationSummaryRfc011IntegrationTest` | `METRICS.md` | ⚠️ 离线 | **保留**(同上) |
| 9 | `bottleneck_diagnosis` (RFC-012) | ⚠️ `AdvancedStatsPanel.jsx:96` `<BottleneckPanel data={state.data?.bottleneck} />` 但读的是 `data.bottleneck`,而 RFC-012 字段名是 `summary.bottleneck_diagnosis`(命名不匹配,**前端 panel 实际不渲染 RFC-012 字段**) | 由 `SimulationRunService.run()` 行 94 注入 | `BottleneckAnalyzerTest` (19) `BottleneckDiagnosisIntegrationTest` (3) | RFC-012 plan(在 `.claude/plans/`) | ⚠️ 离线 | **保留**(第二阶段刚收尾,JSON 字段已存在,前端接入未排期) |
| 10 | multi-seed batch runner (`BatchRunService` / RFC-010A) | ❌ **前端 grep 0 命中** | ❌ **无 `@*Mapping` controller** | `BatchRunServiceTest` (20) `PerSeedMetricExtractorTest` (3) | `improvement_plan_2026-05-21.md` | ⚠️ 离线 / 无运行时入口 | **保留**(被 SensitivityAnalysisService 复用,但同样无运行时入口) |
| 11 | aggregate metrics (`AggregateMetrics` / RFC-010B) | ❌ | ❌ 仅 BatchRunService 内部使用 | `AggregateMetricsCalculatorTest` (11) `ConfidenceIntervalCalculatorTest` (4) | RFC-010B 测试套 | ⚠️ 离线 | **保留**(BatchRunReport 一部分,删除即破坏 RFC-010A schema) |
| 12 | sensitivity analysis (`SensitivityAnalysisService` / RFC-010C) | ❌ | ❌ **无 controller** | `SensitivityAnalysisServiceTest` (13) `WhitelistedParameterMutatorTest` (15) | RFC-010C 测试套 | ⚠️ 离线 / 无运行时入口 | **保留**(刚落地,但**与产线 0 触达**,离线分析能力的纯 Java API) |
| 13 | C++ analyze / batch-analyze | ✅ `runAnalysis` POST `/api/analysis/run` | `AnalysisController.runForReport` `runForScenarios` | `AnalysisControllerIntegrationTest` `ExternalAnalysisServiceTest` | ✅ | ✅ | **保留** |
| 14 | sync optimize `/api/simulation/optimize` | ❌ **前端 grep 0 命中** | `SimulationOptimizationController` 单 endpoint | `SimulationApiIntegrationTest:265,303` 2 用例 + `OptimizationResultBuilderTest` (11) | `API.md` 标 **Deprecated since RFC-005** + `ARCHITECTURE.md` 提及 | ❌ **已声明 deprecated** | **删除候选 P1**(`API.md` 已有 deprecation banner,删除合规) |
| 15 | async optimize `/api/simulation/optimize/async` `/optimize/task/{id}` `/optimize/task/{id}/result` | ❌ **前端 grep 0 命中** | `SimulationOptimizationAsyncController` 3 endpoint | `SimulationOptimizationAsyncControllerIntegrationTest` (8) + `OptimizationTaskServiceTest` (27) | ✅ `API.md` `ARCHITECTURE.md` 当前活路径 | ⚠️ 文档活、前端 0 调 | **暂缓**(文档当前主推为活路径,前端尚未接入但已立 RFC-005 PR-1;**等用户裁决**) |
| 16 | `FULL_REPORTS_DEBUG` (PerSeedMode enum) | ❌ | ❌ 仅 BatchRunService 内 fail-fast | `BatchRunServiceTest:140-145` 1 用例 | `BatchRunRequest.java` javadoc + `PerSeedMode.java` javadoc | ❌ **占位 enum,无近期实现计划** | **删除候选 P1**(同步删 enum 值 + UOE 分支 + UOE 测试 + javadoc) |
| 17 | `WORKLOAD_ROUTING` (QueueChoiceModel enum) | ⚠️ 前端 `simulation.queueChoiceModel.test.js:46,104` 把它视为"未知值降级 STATIC_SPLIT" 的反例 | ❌ 仅 SimulationEngine 内 fail-fast | `QueueChoiceModelPr9bTest:194-202` 1 用例 + `BatchRunServiceTest` 间接 | `QueueChoiceModel.java` javadoc + `USER_GUIDE.md:85` | ❌ **V2/V3 占位,RFC-009 未排期** | **删除候选 P1**(前端测试是反向降级测试,改成"任意未知值"也成立) |
| 18 | `HYBRID_OVERFLOW` (QueueChoiceModel enum) | ❌ 前端无显式引用 | ❌ 仅 SimulationEngine 内 fail-fast | `QueueChoiceModelPr9bTest:204-213` 1 用例 | `QueueChoiceModel.java` javadoc + `USER_GUIDE.md:85` | ❌ **同上** | **删除候选 P1**(同 #17) |

> **附加占位项**(`clean.txt` §3 提到"所有只抛 UOE 的分支"):
>
> | 路径 | 状态 | 建议 |
> |---|---|---|
> | `BatchRunService` 行 69-71 抛 `parallel batch mode not enabled in RFC-010A`(maxParallel ≥ 2 触发) | 测试 `BatchRunServiceTest:206-207` 锁定 | **暂缓**(BatchRunRequest schema 含 `maxParallel` 字段,删需破坏 schema;独立 RFC) |
> | `ReportSummaryStore.compactSummaryStore()` 行 283-284 + `fullResetSummaryStore()` 行 288-289 抛 `disabled in phase 1` | **无任何调用方 / 无任何测试**(grep 0) | **删除候选 P1**(**纯死代码**,见 §3.5) |

---

## 2. 重点审查:废弃 API(`clean.txt` §2)

### 2.1 同步 `/api/simulation/optimize`

| 维度 | 证据 |
|---|---|
| 前端调用 | `grep -i 'optimize\|optimization' sun/src/**` → **0 命中**(2026-05-24 实测) |
| 文档定位 | `API.md:170` 显式写 **"Deprecated since RFC-005"** + `ARCHITECTURE.md:64` 也注 deprecated |
| 响应自标 | `OptimizationService.optimize` 行 53 写 `data.put("deprecated_optimization", true)` |
| 测试覆盖 | `SimulationApiIntegrationTest:265,303` 2 用例(检查 deprecated_optimization=true 与 evaluated_configs 数量) |
| Controller | `SimulationOptimizationController` 单 endpoint 共 **35 行** |
| 上游 service | `OptimizationService`(78 行)— **仅被 sync controller 调用** |
| 共用工具 | `OptimizationResultBuilder.buildItemNode` — 被 sync 与 async 共用,**不能删** |
| 共用 DTO | `OptimizationRequest` — sync 与 async 同一 request 类型,**不能删** |
| 删除影响 | `OptimizationService` 可整体删除;async 用 `OptimizationTaskService` + `OptimizationResultBuilder.buildItemNodeWithError` 不依赖 sync service |

**判断**:`clean.txt` §7 决策规则命中第 1、3、6、9 条(前端不调 ✓ / 文档已标 deprecated ✓ / 仅被旧测试引用 ✓ / 删除后最新功能不受影响 ✓)。**列为 P1 删除候选**。

⚠️ 与第一轮 `CLEANUP_AUDIT.md §4.5` 暂缓结论的差异:
- 第一轮把 `SimulationOptimizationAsyncController` javadoc 写的"字面 0 改动以保留同步路径行为"理解为 maintainer 主动保留,因此暂缓。
- 第二轮 `clean.txt` 明确允许"删除已声明 deprecated 的 API",且 `API.md` + `ARCHITECTURE.md` 都已显式标 deprecated。维护者意图 ≠ 用户意图;**用户明确允许较激进风格**,以 `API.md` 文档现状为准。

### 2.2 异步 `/api/simulation/optimize/async`

| 维度 | 证据 |
|---|---|
| 前端调用 | grep 0 命中 |
| 文档定位 | `API.md:181` `ARCHITECTURE.md:65` 当前活接口,**未标 deprecated** |
| Controller | `SimulationOptimizationAsyncController`(88 行,3 endpoint) |
| 上游 service | `OptimizationTaskService`(本身依赖 SimulationRunService 直接跑,**不依赖 BatchRunService**;512 行) + `OptimizationResultBuilder` |
| 测试覆盖 | `SimulationOptimizationAsyncControllerIntegrationTest` 8 用例 + `OptimizationTaskServiceTest` 27 用例 |

**判断**:与 sync 不同,**文档现状是活接口,未标 deprecated**。前端确实未接入,但 `clean.txt` §8.6 红线"不允许删除当前文档仍声明为主功能的接口,**除非同步更新文档**"。

两条路径:
- **A 保守**:保留 async optimize(35 测试 + 600 行代码),原因:文档是 RFC-005 PR-1 落地的活接口,删除 = 删除一条新生功能。
- **B 激进**:删除 async optimize + 同步更新 API.md / ARCHITECTURE.md,理由:零前端调用 + 第一轮 12 个月内无任何用例。

**建议** A 保守(等用户对 §8 列表裁决)。`clean.txt` §1.15 把 async optimize 列为待审查项,但用户措辞为"是否仍需要",未直接说删,本审计也不擅自决定。

### 2.3 OptimizationService 调用关系

```
OptimizationService.optimize()
  ↓
  SimulationOptimizationController (sync)   ← 仅此一处

OptimizationTaskService.submit() / runBatchInternal()
  ↓
  SimulationRunService.run() 直接调用       ← 不经过 OptimizationService
  ↓
  OptimizationResultBuilder.buildItemNodeWithError()   ← 共用工具
```

`docs/cleanup/CLEANUP_AUDIT.md:258` 写的"OptimizationService 也被新的 OptimizationTaskService(异步路径)用作策略" — **本审计实测后纠正**:async 不依赖 OptimizationService,二者只共用 `OptimizationResultBuilder` 与 `OptimizationRequest` DTO。

---

## 3. 重点审查:占位模式 / UOE 分支(`clean.txt` §3)

### 3.1 `WORKLOAD_ROUTING` / `HYBRID_OVERFLOW` (QueueChoiceModel enum)

| 维度 | 证据 |
|---|---|
| enum 定义 | `dto/QueueChoiceModel.java:17-18` |
| 主代码使用 | `engine/SimulationEngine.java:141-144` 两个 `case` 都抛 `UnsupportedOperationException("V2/V3 not enabled (RFC-009)")` |
| javadoc | `QueueChoiceModel.java:9-11` 注明"PR-9B 阶段未启用 / V2/V3 占位" |
| 测试 | `QueueChoiceModelPr9bTest:194-213` 2 用例锁定 UOE 行为 |
| 前端引用 | `sun/src/utils/simulation.queueChoiceModel.test.js:46,104` — 测试**未知值降级**用 `'WORKLOAD_ROUTING'` 作"非法值"样本 |
| 文档 | `USER_GUIDE.md:85` 直接告知"仍处于 V2/V3 占位,在引擎层 fail-fast" |

**RFC-009 未排期** — 检索 `improvement_plan_2026-05-21.md` v2 全文,无任何"启用 WORKLOAD_ROUTING / HYBRID_OVERFLOW"的计划项。

`clean.txt` §3 判断标准命中:**没有前端入口、没有近期实现计划、只靠测试证明会抛错、只增加 enum / switch 复杂度**。

**建议** P1 删除,联动:
- `dto/QueueChoiceModel.java` 删 2 个 enum 值 + 改 javadoc
- `engine/SimulationEngine.java:139-144` 删 2 个 `case` 分支(switch 默认就足够)
- `service/QueueChoiceModelPr9bTest.java:194-213` 删 2 用例
- `sun/src/utils/simulation.queueChoiceModel.test.js:46-50` 把 `'WORKLOAD_ROUTING'` 替换为 `'INVALID_UNKNOWN_VALUE'`(测试本意是降级未知值,enum 名不影响)
- `USER_GUIDE.md:85` 删除 V2/V3 占位说明
- `QueueChoiceModelPr9bTest` 类的 t9b1 用例无须保留(删除占位即无需 fail-fast 测试)

### 3.2 `FULL_REPORTS_DEBUG` (PerSeedMode enum)

| 维度 | 证据 |
|---|---|
| enum 定义 | `dto/PerSeedMode.java:11` |
| 主代码使用 | `service/BatchRunService.java:73-75` 抛 `UOE("FULL_REPORTS_DEBUG not enabled in RFC-010A")` |
| javadoc | `PerSeedMode.java:7-8` `BatchRunRequest.java:12` `BatchRunReport.java:9` |
| 测试 | `BatchRunServiceTest:138-145` 1 用例锁定 UOE + `BatchRunServiceTest:127,347-350` 2 处反射检查 BatchRunReport 不含 `runs` 字段 |
| 前端 | 0 引用 |

`clean.txt` §3 判断:同上命中。**RFC-010 后续 sub-RFC** 未在 `improvement_plan_2026-05-21.md` v2 中明确排期(只在 javadoc 写"留给后续 sub-RFC"措辞,无具体时间窗口)。

**建议** P1 删除:
- `dto/PerSeedMode.java` 整文件可删(只剩 1 个 enum 值就不必再做枚举,直接在 BatchRunRequest 上去掉 `mode` 字段;**但** schema 已对外发布,见下文权衡)
- 或保守做法:保留 enum 但删 `FULL_REPORTS_DEBUG` 一项 + 删 BatchRunService 抛 UOE 分支 + 删测试用例
- 反射检查的两处"BatchRunReport 不含 runs"可保留(检查 invariant)

权衡:`PerSeedMode` 整删会破坏 BatchRunRequest 序列化 schema(已声明 enum)。**保守做法**只删 `FULL_REPORTS_DEBUG` 值 + 联动测试,enum 作为"单值占位"留下;**激进做法**整删 enum + `mode` 字段。本审计列**保守版**为推荐,**激进版**为可选。

### 3.3 BatchRunService maxParallel ≥ 2 抛 UOE

| 维度 | 证据 |
|---|---|
| 主代码 | `BatchRunService.java:65-71` 检查 `request.getMaxParallel() >= 2` 抛 `parallel batch mode not enabled` |
| 测试 | `BatchRunServiceTest:200-207` 1 用例 |
| 前端 | 0 引用(BatchRunRequest 未对外暴露) |
| 文档 | `BatchRunRequest.java:11` javadoc |

**关联讨论**:这条与 #2 同属 RFC-010A 的 fail-fast 边界。`BatchRunRequest` 的 `maxParallel` 字段也无对外用途。**建议** 与 §3.2 联动,删则一并删,保则一并保。

### 3.4 `compactSummaryStore` / `fullResetSummaryStore` UOE(纯死代码)

| 维度 | 证据 |
|---|---|
| 主代码 | `service/ReportSummaryStore.java:283-289` 两个 public 方法都抛 UOE |
| 调用方 | **grep 0 命中** — 全仓库无任何调用 |
| 测试 | **0 测试**(`ReportSummaryStoreTest` 不覆盖) |
| 文档 | 无 |

**判断**:这两个方法是**纯死代码占位**,既无调用方,也无测试,也无文档,UOE message"phase 1"措辞表明本来就是"先占位、后实现"的预留 API。`clean.txt` §3 严格命中 + §7 决策规则命中第 5、6、8、9 条。

**建议** P0 删除(连同 javadoc 删除两个方法,共 14 行)。

### 3.5 其他 UOE 巡检

`grep "UnsupportedOperationException" src/main/java` 结果:仅上述 4 处 + `SimulationEngine.applyQueueChoiceModel` 的 default 分支(防御性 default,不属"占位")。无遗漏。

---

## 4. RFC-010 / 011 / 012 派生分析链路(`clean.txt` §4)

### 4.1 调用图

```
SimulationRunService.run()  (HTTP 入口活)
  ├─ engine.run()
  ├─ summary.setWindowChoiceMetrics(...)         ← PR-9D,前端 ✅ WindowChoiceMetricsCard
  ├─ summary.setWaitExperienceProxyMetrics(...)  ← RFC-011,前端 ❌
  ├─ summary.setFairnessMetrics(...)             ← RFC-011,前端 ❌
  └─ summary.setBottleneckDiagnosis(...)         ← RFC-012,前端 ⚠️(命名不匹配,见 §1 #9)

OptimizationTaskService.runBatchInternal()  ← /optimize/async,直接调 SimulationRunService

BatchRunService.run()  ← 无 controller,被 SensitivityAnalysisService 调
  └─ AggregateMetricsCalculator.aggregate()

SensitivityAnalysisService.run()  ← 无 controller,无 main caller(grep 全仓 0)
```

### 4.2 入口可达性

| Service / DTO | HTTP 入口 | main caller | 测试 caller | 状态 |
|---|---|---|---|---|
| `SimulationRunService` | ✅ /run /run/async + scenario + analysis | ✅ 多 | ✅ | 活 |
| `BatchRunService` | ❌ | **仅 SensitivityAnalysisService** | ✅ BatchRunServiceTest | 离线 / 间接 |
| `AggregateMetricsCalculator` | ❌ | BatchRunService | ✅ | 离线 |
| `ConfidenceIntervalCalculator` | ❌ | AggregateMetricsCalculator | ✅ | 离线 |
| `SensitivityAnalysisService` | ❌ | **无** | ✅ SensitivityAnalysisServiceTest | **离线 / 完全无运行时入口** |
| `WhitelistedParameterMutator` | ❌ | SensitivityAnalysisService | ✅ | 离线 |
| `PerSeedMetricExtractor` | ❌ | BatchRunService | ✅ | 离线 |
| `OptimizationService` | sync optimize | SimulationOptimizationController | ✅ | 见 §2.1 |
| `OptimizationTaskService` | async optimize | SimulationOptimizationAsyncController | ✅ | 活 |
| `OptimizationResultBuilder` | — | OptimizationService + OptimizationTaskService | ✅ | 活(共用) |
| `BottleneckAnalyzer` | — | SimulationRunService | ✅ | 活(注入 summary) |
| `WaitExperienceProxyCalculator` | — | SimulationRunService | ✅ | 活(注入 summary) |
| `FairnessCalculator` | — | SimulationRunService | ✅ | 活(注入 summary) |

### 4.3 重要观察

1. **整条 RFC-010(A/B/C)在生产路径上没有任何 HTTP/前端入口**。BatchRunService → 仅被 SensitivityAnalysisService 调,SensitivityAnalysisService → main 代码 0 caller。如果用户对此明确"暂时只是离线能力,不接入前端",则全部保留(`clean.txt` §4 注语:不要默认删除 RFC-010/011/012)。
2. RFC-011 / RFC-012 生成的 summary 子树 **每次 run 都被序列化**,但前端无组件读取。这不算冗余 — JSON 字段已宣布,后续前端可直接接入。
3. 没有发现 DTO 重复字段或可合并 calculator(如 `AggregateMetricsCalculator` 用 `ConfidenceIntervalCalculator`,职责清晰分离)。
4. `BottleneckDiagnosis` / `BottleneckEvidence` / `DetectedBottleneck` / `BottleneckType` / `BottleneckSeverity` 5 个 DTO 看起来"过碎",但每个都对应 RFC-012 计划 §设计 中明确列出的字段;不属于"中间态遗留"。

### 4.4 是否有"只被测试使用、无对外入口的 service"

✅ 有 — `SensitivityAnalysisService`、`BatchRunService`、`AggregateMetricsCalculator`、`ConfidenceIntervalCalculator`、`WhitelistedParameterMutator`、`PerSeedMetricExtractor`(共 6 个,加上其 6 个测试套 ~63 用例)。

`clean.txt` §4 提问 6:"如果没有 HTTP / 前端入口,这些离线 service 是否仍应保留?"

**审计回答**:**保留**。原因:
- 这是 RFC-010A/B/C 三轮独立 PR 落地的成果,刚收尾(2026 年 5 月内)
- `clean.txt` §4 明确"不要默认删除 RFC-010/011/012"
- 是离线分析能力,有独立 Java API(其他模块或脚本可调用)
- 用户在 R5 RFC-012 计划里继续依赖这套基础设施

> **保留理由必须写入 `BACKEND_CLEANUP_PLAN.md` §保留项目**(见计划文档 §5)。

---

## 5. 测试套分类(`clean.txt` §5)

按 `clean.txt` §5 的 6 类划分,共统计 59 个 test 文件(不含 `tools/CuratedSamplesGenerator`):

### 5.1 第 1 类:保护当前生产功能

| 文件 | 用途 | 建议 |
|---|---|---|
| `SimulationApiIntegrationTest` | run / async / report / scenario / sync optimize 入口 | **保留**(若 §2.1 删除,**剔除** 第 265 + 303 两个 sync optimize 用例) |
| `SimulationControllerTest` | run 核心行为 | 保留 |
| `SimulationReportRepositoryTest` | report 持久化 | 保留 |
| `SimulationTaskServiceLifecycleTest` | async run 生命周期 | 保留 |
| `SimulationScenario*` / `ScenarioRunServiceContractTest` / `ScenarioPresetNumberFieldStepConformanceTest` | scenario | 保留 |
| `AnalysisControllerIntegrationTest` `ExternalAnalysisServiceTest` | C++ 分析路径 | 保留 |
| `SimulationConfigNormalizerTest` `SimulationRunServiceBoundaryTest` | normalizer / 引擎边界 | 保留 |
| `Seat*Test` `Takeaway*Test` `Group*Test` `Lunch*Test` `Rain*Test` `Sunny*Test` `Theoretical*Test` `Weather*Test` `WaitTime*Test` `TimelineFrameLayoutTest` `Movement / SeatReservation` 等 | 仿真核心行为不变量 | 保留 |
| `ServedFrontendBundleFreshnessTest` | static frontend 资源新鲜度 | 保留 |

### 5.2 第 2 类:保护最新分析指标

| 文件 | 维度 | 建议 |
|---|---|---|
| `WindowChoiceMetricsBuilderTest` `WindowAttractivenessSamplerTest` `WindowSelectionPolicy*` `WindowBalancingTest` `QueueChoiceModelPr9c/d/eTest` | RFC-009 PR-9C..9E | 保留 |
| `WaitExperienceProxyCalculatorTest` (11) `FairnessCalculatorTest` (8) `SimulationSummaryRfc011IntegrationTest` (3) | RFC-011 | 保留 |
| `BottleneckAnalyzerTest` (19) `BottleneckDiagnosisIntegrationTest` (3) | RFC-012 | 保留 |
| `BatchRunServiceTest` (20) `AggregateMetricsCalculatorTest` (11) `PerSeedMetricExtractorTest` (3) `ConfidenceIntervalCalculatorTest` (4) | RFC-010A/B | 保留(若 §3 提议删 FULL_REPORTS_DEBUG/maxParallel UOE,**剔除** 对应 2 个用例 + 2 处反射检查,见 §3.2/§3.3) |
| `SensitivityAnalysisServiceTest` (13) `WhitelistedParameterMutatorTest` (15) | RFC-010C | 保留 |
| `OptimizationTaskServiceTest` (27) `OptimizationResultBuilderTest` (11) | async optimize | 保留(暂缓裁决,见 §2.2) |

### 5.3 第 3 类:保护废弃 API(`clean.txt` §5 允许删)

| 文件 / 用例 | 关联功能 | 建议 |
|---|---|---|
| `SimulationApiIntegrationTest:265-303` 2 用例(sync optimize) | §2.1 P1 删除 | **删除**(若 §2.1 通过) |
| `OptimizationResultBuilderTest` | sync + async 共用工具,async 仍用 | 保留(共用,部分用例如 `parseObjective` 与 sync 路径无关) |

### 5.4 第 4 类:保护只抛 UOE 的占位分支(`clean.txt` §5 允许删)

| 文件 / 用例 | 关联占位 | 建议 |
|---|---|---|
| `QueueChoiceModelPr9bTest:t9b1_workloadRoutingShouldFailFast` `t9b1_hybridOverflowShouldFailFast` 2 用例 | §3.1 P1 | **删除**(若 §3.1 通过) |
| `BatchRunServiceTest:140-145` `failFastWhenModeIsFullReportsDebug` 1 用例 | §3.2 P1 | **删除**(若 §3.2 通过) |
| `BatchRunServiceTest:200-207` `failFastWhenMaxParallelIsTwo` 1 用例 | §3.3 | **删除**(若 §3.3 通过) |
| `BatchRunServiceTest:117-131` `347-350` 反射检查 BatchRunReport 不含 runs 字段 | §3.2 | 保留(invariant,与 enum 是否存在无关) |

### 5.5 第 5 类:保护旧文档契约的测试

未发现纯文档契约测试(所有契约测试都同时锁定运行时行为)。

### 5.6 第 6 类:可合并重命名的 RFC 阶段测试

| 文件 | 命名问题 | 建议 |
|---|---|---|
| `QueueChoiceModelPr9bTest` `Pr9cIntegrationTest` `Pr9dTest` `Pr9eTest` | RFC-009 PR-9B/C/D/E 阶段命名 | **暂缓**(保护活功能,但命名带 PR 编号;`clean.txt` §6 允许合并重命名,本审计建议待 PR-BE-CLEAN-3 后单独做 PR-BE-CLEAN-4) |
| `HistoricalDiagnosticsService*Test` `HistoricalQualityScorer*Test` 各 2 个(Test + BaselineTest) | 重复命名 | 保留(BaselineTest 是独立的快照测试,未重复) |

---

## 6. 文档清理(`clean.txt` §2 提及 API.md)

如 §2.1/2.2/3.1/3.2 各项被删除,**必须同步更新文档**(`clean.txt` §8 红线 #6 / #10):

| 文件 | 行 | 删除关联 | 操作 |
|---|---|---|---|
| `API.md` | 168-179 | §2.1 sync optimize | 删 §`POST /api/simulation/optimize` 整段 |
| `API.md` | 243 | §2.1 | 删提及"`deprecated_optimization` ... 与同步 `/optimize` 响应里的 `true` 区分"句 |
| `ARCHITECTURE.md` | 64 | §2.1 | 删该行 |
| `源代码说明文档.md` | 47 | §2.1 | 改 SimulationController 描述,移除"批处理对比 `/api/simulation/optimize`" |
| `源代码说明文档.md` | 56 | §2.1(若 OptimizationRequest 仍由 async 用)| **保留**(async 仍用) |
| `源代码说明文档.md` | 97 | §2.1 | 改 OptimizationService 描述 → 标记删除;若整删则删行 |
| `USER_GUIDE.md` | 85 | §3.1 | 删 V2/V3 占位说明 |
| `QueueChoiceModel.java` | javadoc | §3.1 | 删 WORKLOAD_ROUTING/HYBRID_OVERFLOW 段 |
| `BatchRunRequest.java` | javadoc 行 11-12 | §3.2/§3.3 | 删 maxParallel/mode 描述 |
| `BatchRunReport.java` | javadoc 行 9 | §3.2 | 删 FULL_REPORTS_DEBUG 引用 |
| `PerSeedMode.java` | javadoc | §3.2 | 整文件删或删 enum 值 |

---

## 7. 推荐删除项汇总

按 `clean.txt` §7 决策规则,优先级排序如下:

### P0 — 纯死代码,无任何争议

1. **`ReportSummaryStore.compactSummaryStore()` + `fullResetSummaryStore()`**(§3.4)
   - 0 调用方 / 0 测试 / 0 文档 / 仅抛 UOE
   - 删除范围:14 行
   - 风险:**0**

### P1 — 已声明 deprecated / 占位且 RFC 未排期

2. **Sync `/api/simulation/optimize` 路径**(§2.1)
   - `SimulationOptimizationController`(35 行)+ `OptimizationService`(78 行)+ `SimulationApiIntegrationTest:265,303` 2 用例
   - 文档同步:`API.md:168-179` `ARCHITECTURE.md:64` `源代码说明文档.md:47,97`
   - 保留 `OptimizationRequest` DTO + `OptimizationResultBuilder`(async 仍用)
   - 风险:低(API.md 已 deprecated banner,前端 0 调)

3. **`WORKLOAD_ROUTING` / `HYBRID_OVERFLOW` 占位 enum**(§3.1)
   - `dto/QueueChoiceModel.java` 删 2 个 enum 值 + 改 javadoc
   - `engine/SimulationEngine.java:141-144` 删 2 个 case 分支
   - `service/QueueChoiceModelPr9bTest:194-213` 删 2 测试用例
   - 文档同步:`USER_GUIDE.md:85`
   - **联动**前端:`sun/src/utils/simulation.queueChoiceModel.test.js:46,104` 把 'WORKLOAD_ROUTING' 替换为 'INVALID_UNKNOWN_VALUE'
   - 风险:低

4. **`FULL_REPORTS_DEBUG` 占位 enum + maxParallel UOE**(§3.2 + §3.3)
   - 保守版:`dto/PerSeedMode.java` 删 `FULL_REPORTS_DEBUG` 项 + `BatchRunService.java:73-75` 删 UOE 分支 + `BatchRunServiceTest:140-145` 删 1 用例
   - 一并:`BatchRunService.java:65-71` 删 maxParallel ≥ 2 UOE + `BatchRunServiceTest:200-207` 删 1 用例
   - 同 javadoc 改 `BatchRunRequest.java` `BatchRunReport.java` `PerSeedMode.java`
   - 激进版(可选):`PerSeedMode` 整文件删 + 从 `BatchRunRequest` 去 `mode` `maxParallel` 字段(破坏 schema,需独立 PR)
   - 风险:保守版低,激进版中(JSON schema 变化)

### P2 — 已声明文档为活路径,前端 0 调,等用户裁决

5. **Async `/api/simulation/optimize/async` 路径**(§2.2)
   - 不在本轮默认删除项里
   - 仅当用户明确"async 也废弃"时,才删 controller + service + 35 测试
   - 风险:中(文档未标 deprecated;删除即 RFC-005 PR-1 整体作废)

### P3 — 测试命名收敛

6. **`QueueChoiceModelPr9b/c/d/eTest` → 重命名为按功能命名**
   - 不在本轮默认执行;若用户同意,作 PR-BE-CLEAN-4

---

## 8. 待用户裁决项

下列每项请用户明确"做 / 不做",回答前不动代码:

| 项 | 默认建议 | 说明 |
|---|---|---|
| ① P0 死代码 `compact/fullResetSummaryStore` | **删** | 完全无副作用 |
| ② P1 Sync `/optimize` 路径 + 2 测试 + 文档 | **删** | API.md 已 deprecated;前端 0 调 |
| ③ P1 `WORKLOAD_ROUTING` / `HYBRID_OVERFLOW` enum + 2 测试 + javadoc | **删** | RFC-009 v2/v3 未排期 |
| ④ P1 `FULL_REPORTS_DEBUG` + maxParallel UOE 保守版(留 enum 单值) | **删** | RFC-010 后续 sub-RFC 未排期 |
| ⑤ P1 `FULL_REPORTS_DEBUG` 激进版(整删 PerSeedMode + BatchRunRequest 字段) | 不动 | 破坏 schema,建议留独立 PR |
| ⑥ P2 Async `/optimize/async` 整路径 + 35 测试 + 文档 | 不动 | 文档当前是活路径 |
| ⑦ P3 RFC 测试命名收敛 | 不动 | 留 PR-BE-CLEAN-4 |
| ⑧ §4.4 离线 RFC-010A/B/C 整链路 | 不动 | 用户在 `clean.txt` §4 已注"不要默认删" |

---

## 9. 红线自检

| 红线(`clean.txt` §8) | 本审计 |
|---|---|
| #1 不改 SimulationEngine 核心仿真行为 | ✅ 仅删 enum case 分支(无运行时影响,2 个值都抛 UOE) |
| #2 不顺手新增功能 | ✅ |
| #3 不改 PREFERENCE_AWARE 逻辑 | ✅ |
| #4 不改 wait_experience / fairness / bottleneck 公式 | ✅ |
| #5 不删前端正在调用的 API | ✅ 已 grep 确认前端 0 调用 sync/async optimize、0 引用 PerSeedMode |
| #6 不删文档仍声明为主功能的接口除非同步更新文档 | ✅ §6 列出每条删除的文档同步项 |
| #7 不为旧测试保留废弃代码 | ✅ §5.3-5.4 列出对应可删测试 |
| #8 不允许一次性巨大提交 | ✅ §拆分到 PR-BE-CLEAN-1..4(见 PLAN 文档) |
| #9 不允许跳过全量测试 | ✅ 计划文档每个 PR 都跑 mvn 全量 + npm |
| #10 不保留"可能以后有用"的占位除非有明确排期 | ✅ §3.1/3.2 已检索 improvement_plan_2026-05-21.md v2 确认无排期 |

---

## 10. 数字小结

| 项 | 数量 |
|---|---|
| 后端 main `.java` | 113 |
| 后端 test `.java` | 59 |
| 前端 `.js/.jsx` | 41 |
| 当前 mvn 用例 | 468(估,基于上一轮基线) |
| 当前 npm 用例 | 62 |
| 推荐删除 main `.java`(P0+P1 默认) | ~3 个文件全删 / ~5 个文件局部 |
| 推荐删除 test `.java` 用例 | ~6 个 @Test 用例 |
| 推荐改前端测试用例 | 1 处替换字符串 |
| 推荐改文档 `.md` | 6 个 |
| 业务行为改动 | 0(全部是已 deprecated 或仅抛 UOE 的占位) |
