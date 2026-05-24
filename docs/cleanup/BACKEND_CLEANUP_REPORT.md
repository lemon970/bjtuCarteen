# BACKEND_CLEANUP_REPORT.md

> 第二轮清洗 — **后端语义级**最终交付报告。基于 `claude` 分支(基线 `b0ca6ed`)派生 `cleanup/backend-be-clean-1..2`,共 **3 个 commit**(2 个 PR + 1 个文档 commit)。

---

## 1. 总览

```
b0ca6ed (claude basline,第一轮 CLEANUP_REPORT.md 收官)
├── 7aa95c0 docs(cleanup): BACKEND_CLEANUP_AUDIT.md + PLAN.md     ← 阶段 1 审计
├── cd9cc4f PR-BE-CLEAN-1: optimize 子系统 + 死代码 ① ② ⑥           ← 阶段 2A
└── 4e7d17f PR-BE-CLEAN-2: 占位 enum 激进删 ③ ④ ⑤                  ← 阶段 2B
```

| 指标 | 第一轮收官 | 第二轮收官 | 变化 |
|---|---|---|---|
| 跟踪文件数(`git ls-files`) | 335 | **327** | -8 |
| 后端测试 | 468 / 0F / 0E | **415 / 0F / 0E** | -53 用例(去掉 optimize + 4 UOE) |
| 前端测试 | 12 文件 / 62 用例 | 12 文件 / 62 用例 | 不变 |
| 前端 build | EXIT 0 | EXIT 0(775KB) | 不变 |
| diff shortstat(b0ca6ed..HEAD)| — | **30 files,+756 / −2264** | — |

业务逻辑、PREFERENCE_AWARE / wait_experience / fairness / bottleneck 公式、SimulationEngine 核心仿真、RFC-010A/B/C 主路径、RFC-011/012 主路径**零修改**。

---

## 2. 用户裁决执行

`clean.txt` §9 8 项裁决,用户批准 6 项执行(① ② ③ ④ ⑤ ⑥),否决 2 项(⑦ ⑧):

| ID | 裁决 | 实施 | Commit |
|---|---|---|---|
| ① | P0 死代码 `compactSummaryStore` / `fullResetSummaryStore` | ✅ 删 | `cd9cc4f` |
| ② | P1 Sync `/api/simulation/optimize` 整路径 | ✅ 删 | `cd9cc4f` |
| ⑥ | P2 Async `/optimize/async` 整路径(联动 ②) | ✅ 删 | `cd9cc4f` |
| ③ | P1 `WORKLOAD_ROUTING` / `HYBRID_OVERFLOW` enum + 引擎 case | ✅ 删 | `4e7d17f` |
| ④ | P1 `FULL_REPORTS_DEBUG` 占位(保守版) | ✅ 删(并入 ⑤ 激进版) | `4e7d17f` |
| ⑤ | P1 `PerSeedMode` 整删 + `BatchRunRequest` 字段(激进版) | ✅ 删 | `4e7d17f` |
| ⑦ | P3 RFC 测试命名收敛 | ❌ 不动 | — |
| ⑧ | RFC-010A/B/C 离线整链路 | ❌ 不动 | — |

---

## 3. PR-BE-CLEAN-1:optimize 子系统 + 死代码(`cd9cc4f`)

### 3.1 主代码删除(7 文件,~1300 行)

| 文件 | 类型 |
|---|---|
| `controller/SimulationOptimizationController.java` | sync `/optimize` |
| `controller/SimulationOptimizationAsyncController.java` | async `/optimize/async` |
| `service/OptimizationService.java` | sync 算法 |
| `service/OptimizationTaskService.java` | async 任务调度 |
| `service/OptimizationTaskRecord.java` | async DTO |
| `service/OptimizationResultBuilder.java` | 共享 builder |
| `dto/OptimizationRequest.java` | 共享 DTO |

### 3.2 测试删除(3 文件)

| 文件 | 用例数 |
|---|---|
| `SimulationOptimizationAsyncControllerIntegrationTest.java` | ~30 |
| `OptimizationResultBuilderTest.java` | ~12 |
| `OptimizationTaskServiceTest.java` | ~13 |

`SimulationApiIntegrationTest.java` 还删了 2 个 sync `/optimize` 集成测试。

### 3.3 死代码

`ReportSummaryStore.java` 删除 `compactSummaryStore()` / `fullResetSummaryStore()` 两个 UOE 方法 + 1 个对应测试。

### 3.4 文档同步

| 文件 | 改动 |
|---|---|
| `API.md` | 删除整个 §4 优化对比节(~90 行),§5 → §4 |
| `ARCHITECTURE.md` | 删 5 行 optimize endpoint 列表;"五类入口" → "四类入口" |
| `源代码说明文档.md` | 3 处删除(SimulationController 描述、`OptimizationRequest.java` 行、`OptimizationService.java` 行) |

### 3.5 .gitignore 修正

第一轮 PR-CLEAN-1 把 `/src/main/resources/static/index.html` 错误加入 `.gitignore` —— 该文件是手写的根入口跳转,不是 vite 产物。本 PR 移除该规则并从 git 历史 `4d4940e` 恢复文件。

---

## 4. PR-BE-CLEAN-2:占位 enum 激进删(`4e7d17f`)

### 4.1 enum 删除

| 文件 | 改动 |
|---|---|
| `dto/QueueChoiceModel.java` | 删 `WORKLOAD_ROUTING` + `HYBRID_OVERFLOW`(2 值);保留 `STATIC_SPLIT` + `PREFERENCE_AWARE` |
| `dto/PerSeedMode.java` | **整文件删除** |

### 4.2 引擎 fail-fast 删除

`SimulationEngine.java:139-145`:删除 `case WORKLOAD_ROUTING` / `case HYBRID_OVERFLOW` 两个 UOE 抛出 + javadoc 同步。

### 4.3 BatchRun schema 收紧(顶层 6 → 5 字段)

| DTO | 改动 |
|---|---|
| `BatchRunRequest.java` | 删 `mode` 字段、`maxParallel` 字段 + 各自 getter/setter + `@JsonAlias("max_parallel")` |
| `BatchRunReport.java` | 删 `mode` final 字段 + 构造器参数 + getter |
| `BatchRunService.java` | 删 `import PerSeedMode`、删 `maxParallel` UOE 检查、删 `mode` UOE 检查、删构造器调用中的 `mode` 参数 |

### 4.4 测试删除/修正

| 文件 | 改动 |
|---|---|
| `QueueChoiceModelPr9bTest.java` | 删 `t9b1_workloadRoutingShouldFailFast` + `t9b1_hybridOverflowShouldFailFast`(2 用例) |
| `BatchRunServiceTest.java` | 删 `t10a3_fullReportsDebugThrowsUoe` + `t10a6_maxParallelGreaterThanOneThrowsUoe`(2 用例);`t10a12_topLevelFieldsAreSixIncludingAggregate` 期望从 6 改为 5(去掉 `"mode"`);删 `import PerSeedMode` |

### 4.5 文档 + 前端测试同步

| 文件 | 改动 |
|---|---|
| `USER_GUIDE.md:85` | 删 V2/V3 占位段落(2 行) |
| `sun/src/utils/simulation.queueChoiceModel.test.js:46,105` | `'WORKLOAD_ROUTING'` → `'INVALID_UNKNOWN_VALUE'`(测试本意是降级未知值,enum 名不影响) |

---

## 5. 验收数据

### 5.1 后端测试

```
mvn -DskipFrontend=true test
[INFO] Tests run: 415, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

基线 468 - 53 删除 = 415,与计划完全对齐。

| PR | 删除测试用例 |
|---|---|
| PR-BE-CLEAN-1 | OptimizationResultBuilderTest + OptimizationTaskServiceTest + AsyncIntegrationTest + sync `/optimize` 集成 2 + ReportSummaryStore 1 = **49** |
| PR-BE-CLEAN-2 | 4(2 Pr9b + 2 BatchRun) |
| 实际差额 | 468 - 49 - 4 = 415 ✅ |

### 5.2 前端测试

```
cd sun && npm test -- --run
Test Files: 12 passed
Tests: 62 passed
```

### 5.3 前端 build

```
npm run build:backend
✓ built in 4.58s
assets/index-DaHL9keg.js   775.04 kB │ gzip: 254.34 kB
```

---

## 6. 红线自检(`clean.txt` §8)

| # | 红线 | 状态 |
|---|---|---|
| 1 | 不允许改 SimulationEngine 核心仿真行为 | ✅ 仅删 V2/V3 fail-fast 分支(死路径),核心 dispatch 逻辑不变 |
| 2 | 不允许顺手新增功能 | ✅ 仅删除,无新增 |
| 3 | 不允许改 PREFERENCE_AWARE 逻辑 | ✅ enum 仍保留 PREFERENCE_AWARE,WindowRoleAssigner / WindowAttractivenessSampler 全 0 改 |
| 4 | 不允许改 wait_experience / fairness / bottleneck 公式 | ✅ RFC-011/012 落地文件全 0 改 |
| 5 | 不允许删除前端正在调用的 API | ✅ optimize / scenarios / timeline endpoint 删除前已通过 `grep -r` 验证前端 0 引用 |
| 6 | 不允许删除文档主功能接口未同步文档 | ✅ API.md / ARCHITECTURE.md / USER_GUIDE.md / 源代码说明文档.md 同 PR 同步 |
| 7 | 不允许为旧测试保留废弃代码 | ✅ 测试随主代码同 PR 删除 |
| 8 | 不允许一次性巨大提交 | ✅ 拆 2 个 PR,每个 PR 独立可回滚 |
| 9 | 不允许跳过全量测试 | ✅ 每个 PR 跑 mvn test + npm test + npm run build:backend |
| 10 | 不允许保留无明确排期占位 | ✅ V2/V3 占位 + FULL_REPORTS_DEBUG 全删,激进版破 schema 已用户批准 |

---

## 7. 不动清单(声明)

本轮**未改动**以下范围:

- `SimulationEngine.java` 仿真核心(仅删 case 分支)
- `WindowAttractivenessSampler` / `WindowRoleAssigner` / `WindowSelectionPolicy`(RFC-009)
- `BatchRunService.run()` 主路径 + `PerSeedMetricExtractor` + `AggregateMetricsCalculator` + `ConfidenceIntervalCalculator` + `SensitivityAnalysisService`(RFC-010A/B/C)
- `WaitExperienceProxyMetrics` / `FairnessMetrics` / 两个 calculator(RFC-011)
- `BottleneckAnalyzer` / `BottleneckDiagnosis` / 4 类 enum(RFC-012)
- `ExternalAnalysisService` / `InternalStatisticsAnalyzer`(C++ 后处理 + Java fallback)
- 前端 React + ECharts + AdvancedStatsPanel
- `pom.xml`(0 依赖变化)

---

## 8. JSON schema 破坏说明(给前端 / 文档维护者)

PR-BE-CLEAN-2 包含 1 处**响应 schema 缩减**:

`POST /api/simulation/batch/run` 响应顶层从 6 字段缩到 5 字段,删除的是 `"mode"` 字段。当前前端不调用该 endpoint(grep `batch/run` / `BatchRunReport` 无前端命中),不构成回归。如未来引入前端调用,直接按 5 字段集对接。

---

## 9. 后续 PR 预留(本轮不执行)

裁决项 ⑦ ⑧ 留作独立 RFC:

- ⑦ RFC 测试命名收敛(`Pr9b/c/d/e` → 按功能命名):纯重命名,优先级低,与功能无关
- ⑧ RFC-010A/B/C 整链路:刚落地能力,清理意义不大,需独立判断

---

**第二轮清洗收官。** 总用时:1 day(2026-05-24)。
