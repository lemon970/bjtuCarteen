# BACKEND_CLEANUP_PLAN.md

> 第二轮清洗 — **后端语义级**删除计划。基于 `BACKEND_CLEANUP_AUDIT.md` 的证据,按 `clean.txt` §6 拆分为 PR-BE-CLEAN-1..4。
> **本文件只是计划,不会自动执行**。等待用户对 §0 的 8 项裁决后,逐 PR 执行。

---

## 0. 用户裁决项(`clean.txt` §9 要求)

执行任何 PR 之前需要用户对下表 8 项明确"做 / 不做"。**默认建议**栏是审计推荐;用户可以全盘接受、部分接受或全部否决。

| ID | 项 | 默认 | 涉及范围 | 风险 |
|---|---|---|---|---|
| ① | P0 死代码 `compactSummaryStore` / `fullResetSummaryStore`(2 方法,14 行) | **删** | `ReportSummaryStore.java` | 0 |
| ② | P1 Sync `/api/simulation/optimize` 整路径(controller + service + 2 测试 + 4 处文档) | **删** | `SimulationOptimizationController` + `OptimizationService` + 测试用例 + `API.md` `ARCHITECTURE.md` `源代码说明文档.md` | 低 |
| ③ | P1 `WORKLOAD_ROUTING` / `HYBRID_OVERFLOW` 枚举 + 引擎 case + 2 测试 + 文档 + 1 处前端测试改字符串 | **删** | `QueueChoiceModel.java` `SimulationEngine.java:141-144` `QueueChoiceModelPr9bTest:194-213` `USER_GUIDE.md:85` `sun/.../simulation.queueChoiceModel.test.js` | 低 |
| ④ | P1 `FULL_REPORTS_DEBUG` 占位 + maxParallel UOE 保守版(留 enum 单值) | **删** | `PerSeedMode.java`(改) `BatchRunService.java:65-75` `BatchRunServiceTest:138-145, 200-207` `BatchRunRequest.java` javadoc `BatchRunReport.java` javadoc | 低 |
| ⑤ | P1 `FULL_REPORTS_DEBUG` 激进版(整删 PerSeedMode + BatchRunRequest 字段) | 不做 | 同 ④ + `BatchRunRequest.mode` `maxParallel` 字段整删 | 中(JSON schema 破坏) |
| ⑥ | P2 Async `/optimize/async` 整路径(controller + service + 35 测试 + 文档) | 不做 | `SimulationOptimizationAsyncController` + `OptimizationTaskService` + `OptimizationResultBuilder` + `OptimizationRequest` 等 | 中 |
| ⑦ | P3 RFC 测试命名收敛(`Pr9b/c/d/e` → 按功能命名) | 不做 | `QueueChoiceModelPr9b/c/d/eTest` 4 文件重命名 | 低(纯命名) |
| ⑧ | §4.4 离线 RFC-010A/B/C 整链路(BatchRunService / SensitivityAnalysisService / 6 测试套 ~63 用例) | 不做 | 全部相关 main + test | 高(刚落地的能力) |

> 用户裁决后,执行下列 PR;每个 PR 单独提交,**绿色测试 → push → review → 下一个 PR**。

---

## 1. 共同执行原则

1. **每条删除项必须能在 `BACKEND_CLEANUP_AUDIT.md` 找到 grep 证据**。
2. 每个 PR 完成后必须跑:
   - `mvn -DskipFrontend=true test`(后端 468 用例,允许减去本 PR 删除用例数)
   - `cd sun && npm test -- --run`(前端 62 用例)
   - `cd sun && npm run build:backend`(确保 frontend bundle 仍能生成)
3. **零回归**才进下一个 PR;有回归立即停下来诊断。
4. 文档同步与代码删除放**同一 PR**,遵守 `clean.txt` §8 红线 #6 / #10。
5. 不修改 git 历史,不强制 push,不跳过 hook(`clean.txt` §8 红线 #8)。
6. 每个 PR 在分支 `cleanup/backend-be-clean-N`(N=1..4),完成后 squash 或保留 commit 由用户定。

---

## 2. PR-BE-CLEAN-1:P0 死代码 + Sync optimize 路径(裁决项 ① + ②)

### 2.1 范围

**A. `ReportSummaryStore` 死代码删除**(裁决项 ①)

- `src/main/java/com/bjtu/simulation/service/ReportSummaryStore.java` 删除:
  - 行 282-285:`public void compactSummaryStore() { throw new UnsupportedOperationException("..."); }`
  - 行 287-290:`public void fullResetSummaryStore() { throw new UnsupportedOperationException("..."); }`
  - 关联 javadoc(若有)

**B. Sync optimize 整路径删除**(裁决项 ②)

- 删除文件:
  - `src/main/java/com/bjtu/simulation/controller/SimulationOptimizationController.java`(35 行)
  - `src/main/java/com/bjtu/simulation/service/OptimizationService.java`(78 行)
- 修改文件:
  - `src/test/java/com/bjtu/simulation/controller/SimulationApiIntegrationTest.java`:删除 sync optimize 相关 2 个 `@Test`(行 ~265 与 ~303 起的两个 method)
  - `API.md`:删除 §168-179 sync `/optimize` 整段;调整行 243 中 `deprecated_optimization` 提及
  - `ARCHITECTURE.md`:删除行 64
  - `源代码说明文档.md`:删除行 47 提及 `/api/simulation/optimize` + 删除行 97 `OptimizationService` 行
- **不动** `OptimizationRequest.java`(async 仍用)
- **不动** `OptimizationResultBuilder.java`(async 仍用,内部 `buildItemNode` 方法 javadoc 提及"sync 路径"可保留或微调,但**不必同 PR 改**)
- **不动** `OptimizationTaskService` / `SimulationOptimizationAsyncController`

### 2.2 验收

```bash
cd /d/desktop/stu/software/src_24281231
mvn -DskipFrontend=true test
# 期望:466/0/0(原 468 减去 2 个 sync optimize 用例)
cd sun && npm test -- --run
# 期望:62/0/0(无变化)
npm run build:backend
# 期望:EXIT 0
```

```bash
# grep 守护
grep -r 'OptimizationService\|SimulationOptimizationController' src/main src/test
# 期望:0 行(全删除)
grep -r '/api/simulation/optimize' src/main src/test sun/src
# 期望:仅 async 端点剩余(/optimize/async, /optimize/task/{id}, /optimize/task/{id}/result)
grep -rn 'compactSummaryStore\|fullResetSummaryStore' src
# 期望:0 行
```

### 2.3 估算

| 项 | 数量 |
|---|---|
| 删除 main `.java` 文件 | 2 |
| 删除 main `.java` 行 | ~127 |
| 删除 test 用例 | 2(sync optimize)+ 0(死代码无测试)= 2 |
| 修改文档 | 3(API.md / ARCHITECTURE.md / 源代码说明文档.md) |
| 修改测试 | 1(SimulationApiIntegrationTest)|

### 2.4 风险

- 若用户后续想恢复 sync optimize,从 git 历史回滚即可;`OptimizationResultBuilder` 仍在,恢复成本不高。
- 文档同步出错可能让用户错过更新;**每个改动文件都要 grep 一遍 `optimize`** 确认残留。

---

## 3. PR-BE-CLEAN-2:占位 enum 删除(裁决项 ③ + ④)

### 3.1 范围

**A. `WORKLOAD_ROUTING` / `HYBRID_OVERFLOW` 删除**(裁决项 ③)

- `src/main/java/com/bjtu/simulation/dto/QueueChoiceModel.java`:
  - 删除 enum 值 `WORKLOAD_ROUTING`、`HYBRID_OVERFLOW`(行 17-18)
  - 修改 javadoc:移除 V2/V3 占位描述
- `src/main/java/com/bjtu/simulation/engine/SimulationEngine.java`:
  - 删除行 141-144 两个 `case`(`switch` 仅剩 STATIC_SPLIT / PREFERENCE_AWARE,与 enum 一致)
  - 行 134 注释也删 V2/V3 提及
- `src/test/java/com/bjtu/simulation/service/QueueChoiceModelPr9bTest.java`:
  - 删除 `t9b1_workloadRoutingShouldFailFast`(行 194-202)
  - 删除 `t9b1_hybridOverflowShouldFailFast`(行 204-213)
  - 修改类 javadoc 第 27 行
- `USER_GUIDE.md:85`:删除"WORKLOAD_ROUTING / HYBRID_OVERFLOW 仍处于 V2/V3 占位..."句
- 前端联动:
  - `sun/src/utils/simulation.queueChoiceModel.test.js`:行 46 改 `'WORKLOAD_ROUTING'` → `'INVALID_UNKNOWN_VALUE'`(测试本意是验证未知值降级,字面值不影响行为);行 104-105 同改
- **不动** SimulationConfigNormalizer 对 PREFERENCE_AWARE / STATIC_SPLIT 的处理逻辑

**B. `FULL_REPORTS_DEBUG` + maxParallel UOE 保守版**(裁决项 ④,不动 ⑤)

- `src/main/java/com/bjtu/simulation/dto/PerSeedMode.java`:
  - 删除 `FULL_REPORTS_DEBUG`(行 11),保留 `METRICS_ONLY` 单值
  - 修改 javadoc(删除 FULL_REPORTS_DEBUG 占位描述)
- `src/main/java/com/bjtu/simulation/service/BatchRunService.java`:
  - 删除 maxParallel ≥ 2 的 UOE 分支(行 65-71 中检查 + 抛出语句)
  - 删除 mode == FULL_REPORTS_DEBUG 的 UOE 分支(行 72-76)
  - 行 28 javadoc 中 FULL_REPORTS_DEBUG 提及移除
- `src/main/java/com/bjtu/simulation/dto/BatchRunRequest.java`:
  - javadoc 行 11-12 移除 maxParallel/mode 描述
- `src/main/java/com/bjtu/simulation/dto/BatchRunReport.java`:
  - javadoc 行 9 移除 FULL_REPORTS_DEBUG 提及
- `src/test/java/com/bjtu/simulation/service/BatchRunServiceTest.java`:
  - 删除 `failFastWhenMaxParallelIsTwo`(行 200-207)
  - 删除 `failFastWhenModeIsFullReportsDebug`(行 138-145)
  - **保留** 行 117-131 与 347-350 的反射检查"BatchRunReport 不含 runs 字段"(invariant,不依赖占位枚举)

> **不进入本 PR**(保留作为 schema):BatchRunRequest 的 `mode` / `maxParallel` 字段本身不删(裁决项 ⑤),只删 fail-fast 检查;请求传 `mode=FULL_REPORTS_DEBUG` 时反序列化会失败(因 enum 值已删),传 `maxParallel=2` 则被忽略(不再检查)。如果需要更严格的 schema,留独立 PR。

### 3.2 验收

```bash
mvn -DskipFrontend=true test
# 期望:462/0/0(466 - 4:Pr9b 2 + BatchRunService 2)
cd sun && npm test -- --run
# 期望:62/0/0
npm run build:backend
```

```bash
grep -rn 'WORKLOAD_ROUTING\|HYBRID_OVERFLOW' src docs sun
# 期望:0 行
grep -rn 'FULL_REPORTS_DEBUG\|parallel batch mode not enabled\|V2/V3 not enabled' src docs
# 期望:0 行
grep -rn 'PerSeedMode' src
# 期望:仅类定义 + METRICS_ONLY 引用
```

### 3.3 估算

| 项 | 数量 |
|---|---|
| 修改 main `.java` 文件 | 5(QueueChoiceModel / SimulationEngine / PerSeedMode / BatchRunService / BatchRunRequest 文档 + BatchRunReport 文档)|
| 修改 main `.java` 行 | ~20 |
| 删除 test 用例 | 4(Pr9b 2 + BatchRun 2)|
| 修改文档 | 1(USER_GUIDE.md)|
| 修改前端测试 | 1(simulation.queueChoiceModel.test.js)|

### 3.4 风险

- enum 值删除会破坏旧客户端发送 `queue_choice_model: "WORKLOAD_ROUTING"`;但前端 grep 已确认非法值降级到 STATIC_SPLIT,反序列化失败也只是 400(并非 5xx)。
- maxParallel 字段保留但不再校验:旧请求传 `maxParallel=4` 不再抛 UOE,而是被忽略(实际仍串行);**等价于"silently ignored"**,与原"explicit fail-fast"行为不同。这是行为变化,但用户许可"激进风格";若严格,改为 maxParallel > 1 时仍记录 warning。

---

## 4. PR-BE-CLEAN-3:RFC-010 / 011 / 012 派生 DTO / service 收敛(可选,**默认跳过**)

### 4.1 结论

**本 PR 默认跳过**,理由(详见 AUDIT §4.2 - §4.4):
- 整条 RFC-010 链路(`BatchRunService` / `SensitivityAnalysisService` / 6 测试套)在 main 代码无 HTTP 入口,但是用户明示"不要默认删除 RFC-010/011/012"。
- RFC-011 / 012 的 summary 子树虽前端未渲染,JSON 字段已宣布(2026-05 第二阶段);裁决项 ⑧ 默认不动。
- 没有发现重复 calculator 或可合并 DTO(每个 calculator 职责单一,DTO 都对应已发布的 schema)。

### 4.2 仅当用户触发裁决项 ⑧ "删除离线 RFC-010 链路"时,才执行此 PR

如触发,删除范围:
- `BatchRunService` / `BatchRunRequest` / `BatchRunReport` / `PerSeedMode` / `PerSeedMetric` / `PerSeedMetricExtractor`
- `AggregateMetrics` / `AggregateMetricsCalculator` / `ConfidenceIntervalCalculator` / `MetricStat` / `CiBounds`
- `SensitivityAnalysisService` / `SensitivityRequest` / `SensitivityReport` / `MetricSensitivityCurve` / `AxisResult` / `ScanAxis` / `WhitelistedParameterMutator` / `WhitelistedParam`
- 关联 6 测试套
- 文档:`METRICS.md` 中 RFC-010 描述

(详细操作步骤待裁决后再展开;不预先列出避免误执行。)

### 4.3 验收

不适用(默认跳过)。

---

## 5. PR-BE-CLEAN-4:测试套命名收敛(可选,**默认跳过**)

### 5.1 结论

**本 PR 默认跳过**,理由:
- `clean.txt` §6 明确允许重命名,但**纯命名**收益低,RFC 阶段编号在文件名是定位 PR / 设计稿的关键索引,合并后丢失。
- 第一轮 `CLEANUP_PLAN.md §5` 同样建议跳过,理由相同(用户当时同意)。
- 若做,应作独立 RFC,**不与本轮逻辑删除同 PR**(`clean.txt` §8.5 提示"不同时做大规模逻辑删除和测试重命名")。

### 5.2 仅当用户触发裁决项 ⑦ 时,才执行

如触发:
- `QueueChoiceModelPr9bTest` → `QueueChoiceStaticSplitInvariantTest`
- `QueueChoiceModelPr9cIntegrationTest` → `PreferenceAwareIntegrationTest`
- `QueueChoiceModelPr9dTest` → `WindowChoiceMetricsContractTest`
- `QueueChoiceModelPr9eTest` → `StickinessPenaltyTest`
- 同步更新各类的 javadoc `@see` / `@since` 引用(若有)

### 5.3 验收

不适用(默认跳过)。

---

## 6. 默认执行顺序

```
1. 用户审阅 BACKEND_CLEANUP_AUDIT.md + 本计划(§0 表格 8 项)
2. 用户对每项给"做 / 不做"
3. 执行 PR-BE-CLEAN-1(裁决项 ① + ②)→ 跑测试 → push 分支 → review
4. 执行 PR-BE-CLEAN-2(裁决项 ③ + ④)→ 跑测试 → push 分支 → review
5. PR-BE-CLEAN-3 / PR-BE-CLEAN-4 默认跳过
6. 全部完成后撰写 BACKEND_CLEANUP_REPORT.md(`clean.txt` 阶段 5)
```

如用户同意全部默认建议(① ② ③ ④ 做,⑤ ⑥ ⑦ ⑧ 不做),实际只跑 PR-BE-CLEAN-1 与 PR-BE-CLEAN-2。

---

## 7. 红线自检

| 红线(`clean.txt` §8) | PR-BE-CLEAN-1 | PR-BE-CLEAN-2 | PR-BE-CLEAN-3/4 |
|---|---|---|---|
| #1 不改 SimulationEngine 核心仿真行为 | ✅ | ✅(只删 case 分支,行为不变) | 默认不做 |
| #2 不顺手新增功能 | ✅ | ✅ | 默认不做 |
| #3 不改 PREFERENCE_AWARE 逻辑 | ✅ | ✅ | 默认不做 |
| #4 不改 wait_experience / fairness / bottleneck 公式 | ✅ | ✅ | 默认不做 |
| #5 不删前端正在调用的 API | ✅(已 grep 0)| ✅ | 默认不做 |
| #6 不删文档仍声明为主功能的接口除非同步更新文档 | ✅(同步删 API.md / ARCHITECTURE.md / 源代码说明文档.md)| ✅(同步改 USER_GUIDE.md)| — |
| #7 不为旧测试保留废弃代码 | ✅(测试一并删) | ✅ | — |
| #8 不允许一次性巨大提交 | 单 PR | 单 PR | — |
| #9 不允许跳过全量测试 | mvn + npm + build:backend | 同左 | — |
| #10 不保留"可能以后有用"的占位除非有明确排期 | ✅(死代码无排期 → 删) | ✅(V2/V3 + FULL_REPORTS_DEBUG 均无 improvement_plan_2026-05-21.md v2 排期 → 删) | — |

---

## 8. 数字小结(默认建议执行部分)

| 项 | 数量 |
|---|---|
| PR 数 | 2(PR-BE-CLEAN-1 + PR-BE-CLEAN-2)|
| 删除 main `.java` 文件 | 2(SimulationOptimizationController + OptimizationService)|
| 修改 main `.java` 文件 | 6 |
| 删除 main `.java` 总行数 | ~145 |
| 删除 test 用例 | 6(sync optimize 2 + Pr9b UOE 2 + BatchRun UOE 2)|
| 修改测试文件 | 2 |
| 修改文档 `.md` | 4(API.md / ARCHITECTURE.md / 源代码说明文档.md / USER_GUIDE.md)|
| 改前端测试 | 1 处字符串替换 |
| 业务行为改动 | 0(只删 deprecated 端点 + 占位 UOE 分支) |
| 期望最终 mvn 用例 | 462(原 468 - 6) |
| 期望最终 npm 用例 | 62(无变化) |
