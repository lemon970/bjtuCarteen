# Logic Review Report

执行时间:2026-05-24 18:50–19:15 UTC+8。本次审查范围对照 `fresh.txt` v1 全条目执行,共发现 6 类文档过期引用,落地 1 个 commit;后端 318 用例 / 前端 71 用例 / `npm run build:backend` 全程绿。

## 1. Baseline

| 项 | 值 |
|---|---|
| Branch | `claude` |
| Start commit | `5dc71a9` (chore(cleanup): delete RFC-011 wait_experience/fairness + QueueTheory dead chain) |
| End commit | (本次报告即将提交,见 §5) |
| Uncommitted files before review | `.gitignore`、`.vscode/settings.json`(均为用户未提交修改,审查中**未触动**) |
| Uncommitted files after review | 同上 + 6 份文档(本次审查产物)|

未提交的 `.gitignore` 和 `.vscode/settings.json` 是用户在本审查会话开始前就已修改的文件,本次审查未读取也未改写其内容,不会和用户工作冲突。

## 2. Commands

| Command | Result | Notes |
|---|---|---|
| `mvn -DskipFrontend=true test`(基线) | ✅ 318 / 0 / 0 | 39.6s |
| `npm test -- --run`(基线) | ✅ 14 files / 71 tests | 7.2s |
| `npm run build:backend`(基线) | ✅ 612 modules → `index-HIgxMepR.js` (779.61 kB) | 3.8s,含既有 chunk-size 警告(非本次新增) |
| `mvn -DskipFrontend=true test`(修后) | ✅ 318 / 0 / 0 | 33.8s |
| `npm test -- --run`(修后) | ✅ 14 files / 71 tests | 6.8s |
| `npm run build:backend`(修后) | ✅ 612 modules,产物哈希未变 | 3.9s,纯文档改动不触发前端代码再编译 |
| `git diff --check` | clean | 无 whitespace 错误 |
| `git status --short` | 仅本次有意改动的 6 份文档 + 用户已有的 2 份(未触动)| — |

## 3. Review Scope

### Backend(`src_24281231/src/main/java`、`src_24281231/src/test/java`)

模块 grep + reading 抽查覆盖:
- 控制层 8 文件:`SimulationController` / `SimulationReportController` / `SimulationScenarioController` / `AnalysisController` / `FrontendController` / `GlobalExceptionHandler` / `ReportResponseBuilder` / `SimulationApiSupport`
- 服务层 19 文件:`SimulationRunService` / `SimulationConfigNormalizer` / `SimulationArrivalScheduler` / `SimulationTimelineBuilder` / `SimulationReportRepository` / `SimulationTaskService`(+ `SimulationTaskRecord`)/ `ScenarioPresetCatalog` / `ScenarioRunService` / `WaitTimeMetricsCalculator` / `BottleneckAnalyzer` / `ExternalAnalysisService` / `InternalStatisticsAnalyzer` / `HistoricalDiagnosticsService` / `HistoricalQualityScorer` / `ReportSummaryStore` / `ReportSummaryExtractor` / `SimulationMath` / `WeatherFactorPolicy`
- DTO 22 文件(全清单见新版 `源代码说明文档.md` §5)
- 引擎 19 文件,重点核 `SimulationEngine`、`WindowSelectionPolicy`、`StudentProfileFactory`、`WindowRoleAssigner`、`WindowAttractivenessSampler`、`WindowChoiceMetricsBuilder`
- 测试套 47 测试类,基线 318 用例

### Frontend(`src_24281231/sun/src`)

- `App.jsx` / `pages/InputPage.jsx` / `pages/DisplayPage.jsx` / `pages/AnalysisPage.jsx`
- `api/simulationApi.js` / `constants.js`
- `utils/simulation.js` / `utils/taskPoller.js` / `utils/useTaskPolling.js` / `utils/asyncRunDecision.js`
- `components/AdvancedStatsPanel.jsx` / `WindowChoiceMetricsCard.jsx` / `BottleneckDiagnosisPanel.jsx` / `HistoricalQualityCard.jsx` 等
- 全部 14 测试文件 / 71 用例

### Docs

- `API.md`、`ARCHITECTURE.md`、`README.md`、`METRICS.md`、`USER_GUIDE.md`、`源代码说明文档.md`
- `docs/cleanup/**`(历史 cleanup 报告,**非当前文档**,不动)
- `docs/superpowers/specs/**`(RFC 草案归档,**非当前文档**,不动)
- `docs/analysis/**`(分析备份与 ADR,**非当前文档**,不动)

## 4. Findings

### P0

| File | Issue | Action |
|---|---|---|
| — | 无 | — |

基线 318 backend / 71 frontend 全绿,无编译错误、无运行崩溃、无核心接口不可用。

### P1

| File | Issue | Action |
|---|---|---|
| `API.md` | 仍声明 SSE `/task/{id}/stream`、`/report/list`、`/cross-scenario`(三者已在 batch 1/2/3 删除);`bottleneck_breakdown { gini, congested_minutes, peak_window }` 形状错(实际是 `bottleneck.{gini_coefficient, worst_window_id, sustained_peak_minutes, score}`);"`source: "java_fallback"`" 字段名+字段值都错(实际 `computed_by: "java-internal"`);未列 RFC-009 PR-9D `window_choice_metrics` 与 RFC-012 `bottleneck_diagnosis` summary 子字段 | 已修(详见 §5) |
| `ARCHITECTURE.md` | 仍声明 `ReportListItemMapper`(已删)、SSE 流、`/cross-scenario`;数据流图仍用 `bottleneck_score` / `anova` 旧字段 | 已修 |
| `README.md` | 写"5 套场景"实际后端目录有 6 套(`group_high_concentration` 漏);声明"跨场景 ANOVA"、`/api/analysis/cross-scenario`(已删);Java fallback 标记字段错(`source: "java_fallback"` → `computed_by: "java-internal"`);快速启动段中"Monte Carlo、ANOVA"两项 Java fallback 实现里都不存在 | 已修 |
| `METRICS.md` | "Queue peak metrics" 段仍列 `peakWindowId`(summary 已无该字段,batch 3 删除);"Advanced statistics" 段全段 schema 错(`bottleneck_breakdown / cross-scenario ANOVA`)| 已修 |
| `USER_GUIDE.md` | "五个内置模型" 实际有 6 套 | 已修 |
| `源代码说明文档.md` | 整体停留在早期项目快照:描述 SSE 推送、批处理对比、`ReportListItemMapper`、`QueueTheoryMetrics`、`QueueTheoryMetricsCalculator`(已删);只列 1 个 controller,实际有 5 个;只列 8 个 engine 类,实际 19 个;只列 7 个 service 类,实际 19 个;子目录段未提及 sun/dataAnalyze | 整体重写,以当前文件清单为权威 |

### P2

| File | Issue | Action |
|---|---|---|
| `engine/SimulationEngine.java`(`getPeakWindowId()`)+ `engine/SimulationSnapshotRecorder.java`(`peakWindowId` 状态机)| `summary.peak_window_id` 在 batch 3 删除后,引擎层 `peakWindowId` 计算/暴露已无 caller(grep 验证 src/sun 下 0 命中)| **不修**:本仓库硬约束"不允许改 SimulationEngine 核心仿真行为"。代码量约 4 行,无编译/测试副作用,记录为 P2 待后续独立 RFC 处理 |
| `InternalStatisticsAnalyzer.java`(line 20 注释)| 类级 javadoc 还提到 `monte_carlo / anova`,但实际只输出 `bottleneck / headline_metrics / confidence_intervals` | 不修(P2 注释错,本轮仅审查文档主表面;独立小修) |
| 历史归档目录(`docs/cleanup/**` / `docs/superpowers/specs/**` / `docs/analysis/**`)| 含已死 endpoint 名、已删类名等 | **不修**:这些是清理工作的 audit / RFC 草案归档,正确的状态就是"保留当时的描述",改动反而会丢历史脉络 |

### P3 / User Decision Required

| File | Issue | Reason Not Changed |
|---|---|---|
| `engine/SimulationSnapshotRecorder.peakWindowId`(死代码) | engine 内部状态机的孤儿代码 | "不允许改 SimulationEngine 核心仿真行为" — 需要用户授权才能动 |
| `InternalStatisticsAnalyzer.java` 类级注释中 `monte_carlo / anova` 残词 | javadoc 与实际输出不符 | 独立小修。本次范围控制在文档主表面;若需要可作下一轮独立提交 |
| 异步任务 SSE 完全删除后的回调路径 | API.md 修改后只剩 polling 一种状态获取路径,`SimulationTaskService` 内部仍有 `subscribers`/`emitUntilComplete` 这一类清理痕迹由 batch 1 处理。本次 grep 确认无残留 | 已 closed,无未决项 |
| `docs/cleanup/**` 与 `docs/superpowers/specs/**` 中的过期描述 | 历史归档,非当前文档 | 不在本审查目标范围 |

## 5. Changes Made

| File | Change | Reason |
|---|---|---|
| `API.md` | (1)删除 `/task/{id}/stream` SSE 段;`/run/async` 末尾改为"通过状态轮询获取进度";(2)删除 `/report/list` 段;(3)删除 `/cross-scenario` 段;(4)`/analysis/run` 响应字段表完整重写为当前 schema(`computed_by` / `confidence_intervals.{wait_time_minutes,seat_utilization_rate}` / `bottleneck.{score,gini_coefficient,worst_window_id,sustained_peak_minutes}` / `headline_metrics`);(5)Summary 字段表新增 `window_choice_metrics`(RFC-009 PR-9D)+ `bottleneck_diagnosis`(RFC-012);(6)降级语义段把"`source: "java_fallback"`" 改为"`computed_by: "java-internal"`" | 与当前 `AnalysisController` / `InternalStatisticsAnalyzer` / `SimulationSummary` 实际实现对齐 |
| `ARCHITECTURE.md` | (1)Report 层删除 `ReportListItemMapper` 表述;Metrics 层删除"队列论指标";Analysis 层去掉"新"字并把 Java fallback 写进同一段;(2)关键接口段删除 SSE 与 cross-scenario 两条,并补 `/report/{id}/csv`;(3)数据流图把 `bottleneck_score / anova` 改成 `bottleneck / headline_metrics` | 与当前控制层路由表 + service 调用图对齐 |
| `README.md` | (1)"5 套场景" → "6 套场景";(2)删除"跨场景 ANOVA"措辞;(3)删除 `/api/analysis/cross-scenario` 示例段;(4)`source: "java_fallback"` → `computed_by: "java-internal"`(README 内 2 处);(5)Java fallback 计算项里删除"Monte Carlo、ANOVA"(实际不输出) | 与 `ScenarioPresetCatalog`(6 preset)+ `AnalysisController`(单 endpoint)+ `InternalStatisticsAnalyzer` 输出 schema 对齐 |
| `METRICS.md` | (1)Queue peak metrics 段删除 `peakWindowId` 行;(2)Advanced statistics 整段重写为当前 schema(同 API.md);删 ANOVA 段 | 与 batch 3 删 `summary.peak_window_id` + 当前 analyzer 输出对齐 |
| `USER_GUIDE.md` | "五个内置模型" → "六个内置模型",末尾追加"高成组浓度模型" | 与 ScenarioPresetCatalog 对齐 |
| `源代码说明文档.md` | 整体重写。子目录段补 sun/dataAnalyze;控制层从 1 项扩到 8 项;DTO 从 6 项扩到 22 项,并标注 RFC-009 / RFC-012 子树;引擎从 8 项扩到 19 项;服务层从 7 项扩到 19 项;删除 SSE 推送、批处理对比、ReportListItemMapper、QueueTheoryMetrics 等已删项;增加阅读建议步骤 5 覆盖瓶颈分析 + Java fallback | 文档严重过期,按当前 `controller/`、`service/`、`engine/`、`dto/` 真实文件清单重写 |

## 6. Tests

| Command | Result |
|---|---|
| `mvn -DskipFrontend=true test`(修后) | ✅ Tests run: 318, Failures: 0, Errors: 0, Skipped: 0(33.844s) |
| `npm test -- --run`(修后,sun 目录) | ✅ Test Files 14 passed / Tests 71 passed(6.79s) |
| `npm run build:backend`(修后) | ✅ 612 modules → `static/frontend/index-HIgxMepR.js` (779.61 kB),哈希未变 |
| `git diff --check` | clean |

> 本次只改文档,不触发任何源代码变更,因此 218 + 71 测试套和前端 chunk 哈希都不应变 — 实测证实未变。

## 7. Scope Guard

确认以下事项:

- ✅ No new backend endpoint(controller 表全程不变)
- ✅ No new controller(8 个 controller 全部已存在)
- ✅ No `AnalysisTaskService`(grep 0 命中,未新增)
- ✅ No `BatchScanPage`(前端无新增页面)
- ✅ No RFC-010A/B/C productization(`docs/superpowers/specs/2026-05-24-rfc010-012-frontend-design.md` 仍是 spec,未改)
- ✅ No optimize subsystem restoration(grep `OptimizationService` / `SimulationOptimizationController` / `OptimizationTaskService` 在 main 源码 0 命中)
- ✅ No dependency changes(`pom.xml` / `package.json` 未触动)
- ✅ No C++ main logic changes(`dataAnalyze/` 未触动)
- ✅ No broad formatting-only changes(diff 控制在文档段落级别,未做整体格式化)
- ✅ No SSE re-introduction(API.md / ARCHITECTURE.md 中 SSE 表述全部删除,与 batch 1 删除一致)
- ✅ No removal of frontend-called API(`/api/analysis/run` + `include_historical_*` 两个 flag 均保留,`AdvancedStatsPanel` 仍可用)
- ✅ No test deletion / weakening(0 测试改动)

## 8. Remaining Items

不在本轮处理,留作后续:

1. **`SimulationEngine.getPeakWindowId()` + `SimulationSnapshotRecorder.peakWindowId`** 死代码:`summary.peak_window_id` 已删但 engine 内仍计算与暴露。受"不允许改 SimulationEngine 核心仿真行为"约束保留;独立 RFC 处理。
2. **`InternalStatisticsAnalyzer.java` 类级 javadoc 中 `monte_carlo / anova` 残词**:实际不输出该字段,javadoc 误导。本次范围控制在文档主表面;独立小修可一行删除。
3. **历史归档目录的过期表述**(`docs/cleanup/**`、`docs/superpowers/specs/**`、`docs/analysis/**`):是设计阶段的 audit / RFC 草案,**故意保留**当时的描述;不在本轮范围。
4. **`HistoricalQualityScorer` / `HistoricalDiagnosticsService`**:在前一轮 batch 4 已与用户对齐**保留**(`AdvancedStatsPanel` 实际调用),不属于本轮新发现。
