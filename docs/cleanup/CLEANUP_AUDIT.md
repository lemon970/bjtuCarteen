# CLEANUP_AUDIT.md

> 阶段 1 全仓库审计。本文件**只读**:列出候选 + 引用证据,不动任何代码。
> 删除清单见 `CLEANUP_PLAN.md`。

---

## 0. 基线

| 项目 | 值 |
|---|---|
| Git root | `D:\desktop\stu\software\src_24281231\.git` |
| 起始分支 | `claude` |
| 清洗分支 | `cleanup/remove-dead-code`(已创建,基于 `claude`) |
| 工作树初始改动 | 仅 `.vscode/settings.json`(与本次清洗无关) |
| 跟踪文件总数 | 401 |
| `mvn -DskipFrontend=true test` | **468 测试 / 0 失败 / BUILD SUCCESS** |
| `cd sun && npm test -- --run` | **12 文件 / 62 测试 / 全过** |
| `cd sun && npm run build:backend` | **611 modules → static/frontend/,EXIT=0** |

最近 5 commit:

```
9c984fa feat(analysis): add bottleneck diagnosis summary metrics (RFC-012)
a5bf4f3 feat(analysis): add wait experience proxy and fairness metrics (RFC-011)
095ed0e Add unit tests for confidence interval calculations, per seed metrics extraction, sensitivity analysis, and whitelisted parameter mutation
272dd2c add new html
fc2fd03 debugs for new logical
```

---

## 1. 顶层目录盘点

| 目录 | 类型 | 跟踪文件数 | 磁盘大小 | 是否保留 | 备注 |
|---|---|---|---|---|---|
| `src/main/java` | 后端生产 | 113 | — | ✅ 保留 | Spring Boot 主代码 |
| `src/test/java` | 后端测试 | 59 | — | ✅ 保留(局部清理) | 含 1 个 tools generator |
| `src/main/resources/static` | **build 产物** | 4 | — | ⚠️ P0 删除 + .gitignore 加规则 | 由 `vite build` 写入 |
| `sun/src` | 前端源码 | 31 + 12test | — | ✅ 保留(全活) | App.jsx 引用图见 §3 |
| `sun/dist` | build 产物 | 0 | 801 K | P0 物理删除 | 已在 .gitignore,无跟踪 |
| `dataAnalyze` | C++ 模块 | 10 + 1 .user | 110 K | ✅ 保留 + 局部清理 | 见 §5 |
| `target` | Maven build 产物 | **59** | 92 M | ⚠️ **P0 git rm + 物理删除** | .gitignore 已含,但历史 commit 把 target/classes 提交进去了 |
| `reports` | 运行产物 | 0 | **777 M** | P0 物理删除(已 gitignore) | 跟踪 0,但占盘巨大 |
| `analysis-store` | 运行产物 | 0 | 1.8 M | P0 物理删除(已 gitignore) | 跟踪 0 |
| `samples/curated-history` | 历史样本 | **81** | 420 K | ⚠️ P3 暂缓(见 §6) | 仅 `CuratedSamplesGenerator.java` 写,无运行时读 |
| `examples/scenarios` | 示例 JSON | 1 | 1 K | ✅ 保留 | README §… 直接引用 |
| `docs` | 文档 | **2**(`rfc-002/003`) | — | ⚠️ P2 评估 | 内容是已落地 RFC 的设计稿 |
| `.vscode` | IDE 配置 | 3 | — | ⚠️ P3 评估 | 项目级共享 IDE 设置 |
| `*.md`(根) | 文档 | 10 | — | 见 §7 | 1 keep + 9 评估 |
| `pom.xml / start.ps1 / .gitignore / .editorconfig` | 构建脚手架 | 4 | — | ✅ 保留 | |

> .gitignore 已经覆盖 `target/, reports/, analysis-store/, sun/dist/, dataAnalyze/Project3/x64/, dataAnalyze/Project3/Debug/, dataAnalyze/Project3/Release/`。**问题在于**`target/classes/**` 在 .gitignore 加入之前就已被 commit,所以 `git ls-files target/` 返回 59 文件。需 `git rm -r --cached target` 把它们退出跟踪。

---

## 2. P0 候选:构建产物 / 运行产物 / 旧入口

### 2.1 跟踪中的 build 产物(必删)

`git ls-files | grep -E '^target/' | wc -l` → **59 文件**,包括:

```
target/classes/application.yml
target/classes/com/bjtu/simulation/**/*.class       (~50 个 .class)
target/classes/static/demo.html
target/classes/static/frontend/index.html
target/classes/static/index.html
target/maven-status/maven-compiler-plugin/...lst    (4 个)
target/test-classes/com/bjtu/simulation/controller/SimulationApiIntegrationTest.class
target/test-classes/com/bjtu/simulation/controller/SimulationControllerTest.class
```

→ `git rm -r --cached target` + 物理删除 + 已在 .gitignore,无后续动作。

### 2.2 跟踪中的前端 build 产物(必删)

```
src/main/resources/static/frontend/assets/index-BEfG-hW-.css
src/main/resources/static/frontend/assets/index-DaHL9keg.js
src/main/resources/static/frontend/index.html
src/main/resources/static/index.html
```

证据:`sun/vite.config.*` 配 `outDir: '../src/main/resources/static/frontend'`,`npm run build:backend` 输出本目录。这 4 文件是 build 输出,**不应跟踪**。

→ `git rm -r --cached src/main/resources/static/frontend src/main/resources/static/index.html` + 加 `.gitignore`(`/src/main/resources/static/frontend/` 与 `/src/main/resources/static/index.html`)。`mvn` build 时 `npm run build:backend` 会重新生成。

⚠️ **风险**:`ServedFrontendBundleFreshnessTest`(`src/test/java/com/bjtu/simulation/release/`) 可能依赖该目录存在 — 必须 build 后再跑 mvn,这是已有约束。验证步骤会在 PR-CLEAN-1 后跑 mvn 确认。

### 2.3 物理大对象(已 gitignore,只占盘)

| 路径 | 跟踪 | 大小 | 处理 |
|---|---|---|---|
| `target/` | 含 §2.1 | 92 M | 拉黑 + 物理删除 |
| `reports/` | 0 | 777 M | 物理删除(`rm -rf`)|
| `analysis-store/` | 0 | 1.8 M | 物理删除 |
| `sun/dist/` | 0 | 801 K | 物理删除 |

合计回收 ~870 M 磁盘。

### 2.4 `target/classes/static/demo.html`

仅出现在 `target/classes/`,**源码侧无 demo.html**(`git ls-files | grep -i demo` 只返回 target 路径)。它是 build 把 src/main/resources/static/index.html 复制时一同带入的旧入口。删 §2.1 即一并消失。

---

## 3. 前端审计(sun/src)

### 3.1 引用图(从 `main.jsx` 出发)

```
main.jsx
└── App.jsx
    ├── api/simulationApi.js  (8 个 export 全部被 App.jsx 调用)
    ├── components/AppLayout.jsx
    │   └── DataStatusPill.jsx
    ├── pages/InputPage.jsx
    │   └── utils/simulation.js
    ├── pages/DisplayPage.jsx
    │   ├── ChartPanel.jsx
    │   ├── HistoryTable.jsx
    │   ├── MetricCard.jsx
    │   ├── ScenarioCompareTabs.jsx
    │   ├── SeatHeatmap.jsx
    │   ├── TakeawayRatePanel.jsx
    │   ├── TimelinePlayer.jsx
    │   ├── WaitTimePanel.jsx
    │   │   ├── charts/WaitDistributionBar.jsx
    │   │   ├── InsightNarrative.jsx
    │   │   └── MetricCard.jsx
    │   ├── charts/QueueBarChart.jsx
    │   ├── charts/SeatUtilizationLine.jsx
    │   ├── charts/TrendChart.jsx
    │   └── utils/useEcharts.js
    ├── pages/AnalysisPage.jsx
    │   ├── AdvancedStatsPanel.jsx
    │   │   └── HistoricalQualityCard.jsx
    │   ├── ChartPanel.jsx
    │   ├── InsightNarrative.jsx
    │   ├── MetricCard.jsx
    │   ├── charts/SeatUtilizationLine.jsx
    │   ├── charts/TrendChart.jsx
    │   ├── WaitTimePanel.jsx
    │   ├── WindowChoiceMetricsCard.jsx
    │   └── utils/simulation.js
    ├── utils/asyncRunDecision.js
    │   └── utils/simulation.js
    ├── utils/simulation.js
    │   └── constants.js
    ├── utils/useTaskPolling.js
    │   └── utils/taskPoller.js
    └── constants.js
```

**结果**:21 个 `.jsx` 组件 + 5 个 `.js` utils + `constants.js` + `App.jsx` + `main.jsx` 全部从根可达。

### 3.2 测试文件验证

12 个 test 文件,**每一个都对应活源**:

| 测试文件 | 测试目标 | 目标在 §3.1 引用图? |
|---|---|---|
| `App.async.test.jsx` | App.jsx 异步 polling | ✅ |
| `api/simulationApi.test.js` | api 客户端 | ✅ |
| `components/HistoricalQualityCard.test.jsx` | HistoricalQualityCard | ✅ |
| `components/WindowChoiceMetricsCard.test.jsx` | WindowChoiceMetricsCard | ✅ |
| `pages/InputPage.bug2.test.jsx` | InputPage | ✅ |
| `pages/InputPage.queueChoiceModel.test.jsx` | InputPage | ✅ |
| `pages/InputPage.runMode.test.jsx` | InputPage | ✅ |
| `utils/asyncRunDecision.test.js` | asyncRunDecision | ✅ |
| `utils/simulation.queueChoiceModel.test.js` | simulation.js | ✅ |
| `utils/simulation.test.js` | simulation.js | ✅ |
| `utils/taskPoller.test.js` | taskPoller | ✅ |
| `utils/useTaskPolling.test.jsx` | useTaskPolling | ✅ |

### 3.3 前端结论

**前端无候选删除**。所有 `.jsx`、`.js`、`.css`、test 都在引用图内。
旧 mock / 占位代码 / 空组件 / 死 utils → **零命中**。

---

## 4. Java 后端审计(src/main/java + src/test/java)

### 4.1 包结构分布

```
controller       9 main / 4 test
config           1 main
dto              43 main / 0 test (POJO 不需要)
engine           18 main / 11 test
model             9 main / 0 test
service          39 main / 35 test (大头)
release          0 main / 1 test (ServedFrontendBundleFreshnessTest)
tools            0 main / 1 test (CuratedSamplesGenerator)
SimulationApplication.java
合计           113 main / 59 test
```

### 4.2 Deprecation / placeholder 标记 grep

`grep -E "@?Deprecated|UnsupportedOperationException|FULL_REPORTS_DEBUG|TODO|FIXME|legacy|_old|_V1|_V2"`:

| 标记 | 出现位置 | 性质 |
|---|---|---|
| `FULL_REPORTS_DEBUG` | `dto/PerSeedMode.java`(enum 占位) + `dto/BatchRunRequest.java` javadoc + `service/BatchRunService.java`(主动抛 UOE) + `service/BatchRunServiceTest.java`(锁定 UOE 行为) + `dto/BatchRunReport.java` javadoc | RFC-010A 故意保留的占位 enum,主动 fail-fast。删除会引入 schema 变化(BatchRunRequest 序列化反向兼容性)。**留** — 不属于"占位且不再计划实现"。 |
| `case WORKLOAD_ROUTING / HYBRID_OVERFLOW` | `engine/SimulationEngine.java` 抛 UOE | RFC-009 v2 占位枚举,QueueChoiceModel 列出 PR-9B 阶段未启用。**留**(主动 fail-fast)。 |
| `compactSummaryStore` / `fullResetSummaryStore` | `service/ReportSummaryStore.java` 抛 UOE | "phase 1 disabled"占位。仅 `ReportSummaryStoreTest` 测 UOE 行为。**留 / 暂缓**(同口径 fail-fast)。 |
| "legacy 1.0 schema" 字符串 | `HistoricalQualityScorer.java` 注释 + 测试 | 历史数据兼容分支,实际承担"无 baseline 子树时回退"语义,非废弃。**留**。 |
| `legacy-group-` 字符串 | `SimulationArrivalScheduler.java` 第 303 行 | 日志/标识前缀,非废弃。**留**。 |
| `Deprecated` 注解 | 0 命中(仅出现在测试注释里) | — |
| `_old / _V1 / _V2` | 0 命中 | — |
| `TODO / FIXME` | 0 实质命中 | — |

→ Java 后端**无明显的死占位 / 旧版本残留**。所有 UOE 都有故意 fail-fast 语义。

### 4.3 Controller / Endpoint 引用证据

| Controller | 端点 | 前端调用? | 测试调用 | 状态 |
|---|---|---|---|---|
| `SimulationController` | `/api/simulation/run`、`/run/async`、`/task/{id}/status`、`/task/{id}/stream` | ✅ App.jsx 全用 | ✅ | 保留 |
| `SimulationReportController` | `/api/simulation/report/...` | ✅ App.jsx 全用 | ✅ | 保留 |
| `SimulationScenarioController` | `/api/simulation/scenarios`、`/scenarios/run` | ✅ | ✅ | 保留 |
| `AnalysisController` | `/api/analysis/run`、`/cross-scenario` | ✅ AdvancedStatsPanel | ✅ | 保留 |
| `FrontendController` | `/frontend` | (服务端转发,Spring 路由) | — | 保留 |
| `SimulationOptimizationAsyncController` | `/api/simulation/optimize/async`、`/task/{id}`、`/task/{id}/result` | ❌ **前端 grep "optimize" 0 命中** | ✅ `SimulationOptimizationAsyncControllerIntegrationTest` (10 用例) | ⚠️ **P3 暂缓**:见 §4.5 |
| `SimulationOptimizationController` | `/api/simulation/optimize`(同步) | ❌ | ✅ `SimulationApiIntegrationTest:265, 303` | ⚠️ **P3 暂缓**:见 §4.5 |
| `GlobalExceptionHandler` | (Spring) | — | — | 保留 |

### 4.4 Service 引用证据(抽样)

每个 main/service 都至少被一个 controller 或同包 service 调用。抽样:

| Service | 上游调用 |
|---|---|
| `SimulationRunService` | `SimulationController` + `SimulationTaskService` + `BatchRunService` + `OptimizationService` |
| `BatchRunService` | `SensitivityAnalysisService` + `OptimizationTaskService`(grep) + 测试 |
| `SensitivityAnalysisService` | `OptimizationTaskService`(预期)+ 测试 13 用例 |
| `BottleneckAnalyzer` | `SimulationRunService.run` 第 94 行(本轮 RFC-012 刚接入)+ 测试 19 用例 |
| `OptimizationService` | `SimulationOptimizationController`(同步路径) |
| `OptimizationTaskService` | `SimulationOptimizationAsyncController`(异步路径) |
| `HistoricalDiagnosticsService` | `AnalysisController` |
| `HistoricalQualityScorer` | `AnalysisController` |
| `ExternalAnalysisService` | `AnalysisController` |
| `InternalStatisticsAnalyzer` | `ExternalAnalysisService` 兜底 |
| `ReportSummaryStore` / `ReportSummaryExtractor` / `ReportListItemMapper` | `SimulationReportController` + `HistoricalDiagnosticsService` |
| `ScenarioPresetCatalog` / `ScenarioRunService` | `SimulationScenarioController` |

→ **后端 Service 层无孤儿**。

### 4.5 ⚠️ Optimize 路径(P3 暂缓核心争议项)

**事实**:
- 前端 `sun/src/**/*.{js,jsx}` grep `optimize|optimization` → **0 命中**(已确认)
- Sync `/api/simulation/optimize` 端点:仅被 `SimulationApiIntegrationTest:265,303` 调用(2 用例)
- Async `/api/simulation/optimize/async` 端点:仅被 `SimulationOptimizationAsyncControllerIntegrationTest`(独立测试类,10 用例)调用
- `OptimizationService` 也被新的 `OptimizationTaskService` / `OptimizationResultBuilder`(异步路径)用作策略,即:**sync controller 删了仍有产线场景**(异步路径还在用)

**maintainer intent 证据**:
- `SimulationOptimizationAsyncController` javadoc:"新 controller,旧 SimulationOptimizationController **字面 0 改动以保留同步路径行为**"
- 这是**主动保留**而非疏忽

**clean.txt §3.2#2** 红线:"不允许为了让旧测试通过保留明显废弃的旧代码"。**但**:
- 这里 maintainer 的意图是"保留两套对外路径"(同步 + 异步),不是"为了测试保留"
- **如果**前端确认永不切回同步路径 → P1 删除 sync controller + sync test;但这是**接口契约变更**,违反 clean.txt §3.2#5 "不修改业务行为,除非该行为本身属于明确废弃功能"
- **判断分歧点**:同步 `/optimize` 是否"明确废弃"?javadoc 说不是

→ 推荐 **P3 暂缓**,在 PLAN 中给 user 明确二选一。

### 4.6 Test 碎片化(P2 收敛候选)

按 RFC / PR 编号碎片化的 test 类:

| 测试类 | 测试焦点 | 总用例数(抽样) |
|---|---|---|
| `engine/QueueChoiceModelPr9cIntegrationTest` | RFC-009 PR-9C weighted sampling | — |
| `engine/QueueChoiceModelPr9eTest` | RFC-009 PR-9E stickiness | — |
| `service/QueueChoiceModelPr9bTest` | RFC-009 PR-9B fail-fast UOE | — |
| `service/QueueChoiceModelPr9dTest` | RFC-009 PR-9D window_choice_metrics | — |
| `service/AggregateMetricsCalculatorTest` | RFC-010B | — |
| `service/BatchRunServiceTest` | RFC-010A | — |
| `service/PerSeedMetricExtractorTest` | RFC-010A | — |
| `service/SensitivityAnalysisServiceTest` | RFC-010C | — |
| `service/BottleneckAnalyzerTest` + `BottleneckDiagnosisIntegrationTest` | RFC-012 | 19 + 3 |
| `service/SimulationSummaryRfc011IntegrationTest` | RFC-011 | 3 |

**P2 候选**:`Pr9b/Pr9c/Pr9d/Pr9e` 4 个文件**可考虑**合并为 `QueueChoiceModelTest`(per `clean.txt §8.5` 提示),但这是测试**重命名 + 内部重组**,clean.txt §8.5 警告"不要在同一 PR 里同时做大规模逻辑删除和测试重命名"→ 留作 PR-CLEAN-5 单独处理,且**收益小**(仅命名,行为不变),建议本轮**不做**,标 P3 / 不必要。

### 4.7 测试侧清理候选

| 文件 | 状态 |
|---|---|
| `src/test/java/com/bjtu/simulation/tools/CuratedSamplesGenerator.java` | tools 类,**仅生成**`samples/curated-history/{reports,summaries,manifest.json}`。无运行时 reader。是否保留取决于 §6 决定。 |

---

## 5. C++ dataAnalyze 模块审计

### 5.1 跟踪文件(10 个 + 1 个 .user)

```
dataAnalyze/AnalysisCore.cpp       (~313 行,Java 调用入口)
dataAnalyze/AnalysisCore.h         (~120 行)
dataAnalyze/JsonUtil.h             (~342 行,自实现 JSON,无外部依赖)
dataAnalyze/CMakeLists.txt         (CMake 路径)
dataAnalyze/Project3.sln           (VS 解决方案)
dataAnalyze/Project3.vcxproj       (VS 工程)
dataAnalyze/Project3.vcxproj.filters
dataAnalyze/Project3.vcxproj.user  ⚠️ 通常 IDE 私有,但已被跟踪
dataAnalyze/DiningSimulation.cpp   (~870 行,仅 --mode=simulate 路径用)
dataAnalyze/DiningSimulation.h     (~6.7 K)
dataAnalyze/源.cpp                 (CLI main, 153 行,subcommand 分发)
dataAnalyze/README.md
```

### 5.2 调用证据

- `ExternalAnalysisService.java` 通过 `ProcessBuilder` 调 `canteen-analyze --mode=analyze` 与 `--mode=batch-analyze`
- `--mode=simulate` 子命令在 `源.cpp` 里硬编码 demo config 后调 DiningSimulation.{h,cpp}。**Java 侧从未传 simulate 模式**(grep 验证)
- → DiningSimulation.{h,cpp} 是**事实生产路径死代码**,但仍是 C++ 工程一部分
- 上一轮(rebuild.txt)已对此模块做过详细审计,结论一致

### 5.3 dataAnalyze 候选分类

| 文件 | 处理建议 | 原因 |
|---|---|---|
| `AnalysisCore.{cpp,h}` / `JsonUtil.h` / `源.cpp` / `CMakeLists.txt` | ✅ 保留 | 现役 |
| `Project3.sln` / `vcxproj` / `vcxproj.filters` | ✅ 保留 | VS 工具链入口(README 推荐) |
| `Project3.vcxproj.user` | ⚠️ **P2 候选**:从跟踪移除 | 通常 IDE 私有,.gitignore 已含 `*.vcxproj.user` 但**该文件被 .gitignore 之前 commit 了** |
| `DiningSimulation.{cpp,h}` | ⚠️ **P3 暂缓** | 不在生产路径,但属于 C++ 子工程;rebuild.txt 已审计;删除影响 vcxproj/CMakeLists,跨工具链 |
| `README.md` | ⚠️ P2 评估 | 上一轮 audit 指出该 README 与实际状态有出入(描述了 simulate 路径作为主路径) |
| `dataAnalyze/.vs/` | 已 gitignore,跟踪 0 | 物理删除即可 |
| `dataAnalyze/Project3/x64/` | 已 gitignore,跟踪 0 | 已正确 |

---

## 6. samples / examples 审计

### 6.1 `samples/curated-history/`(81 跟踪文件)

| 文件 | 跟踪 | 上游(grep) | 下游 reader |
|---|---|---|---|
| `manifest.json` | ✅ | `CuratedSamplesGenerator.java` 写出 | grep `manifest.json` 在生产代码 → **0 命中** |
| `summaries/curated-{A..P}*.summary.json` | ✅ × 80 | 同上 | 同上 → **0 命中** |
| `reports/curated-*.json` | 0 跟踪(应该是被 .gitignore 的 reports/ 子树) | — | — |

**事实**:`samples/curated-history` 是 **CuratedSamplesGenerator 跑出来的离线产出**,生产代码无 reader。

**潜在用途**:可能是 HistoricalDiagnostics / HistoricalQualityScorer 的"历史快照"参考数据,但**没有任何 Java 路径在 runtime 读 `samples/`**(grep 印证)。

→ **P3 暂缓**:用户需明确 — 如果这些 summary 是仅作"答辩演示"用,可以连同 generator 一并 P1 删除;如果以后还要做 baseline 校准,必要保留。

### 6.2 `examples/scenarios/canteen-scenario-set.json`(1 文件)

引用证据:`README.md:91:[examples/scenarios/canteen-scenario-set.json](...)`。

→ **保留**。仅 README 文本引用,非代码消费,但作为示例资产合理。

---

## 7. 文档审计

### 7.1 根目录 .md(10 个)

| 文件 | 主题 | 实施状态 / 与代码一致性 | 建议 |
|---|---|---|---|
| `README.md` | 项目入口,说明启动 / 架构 | 标题"大学食堂高峰客流仿真系统"贴合实际 | ✅ 保留 |
| `USER_GUIDE.md` | 信息输入 / 显示 / 分析三页 | 与前端三页一致 | ✅ 保留 |
| `ARCHITECTURE.md` | 模块结构 | 抽样核对一致 | ✅ 保留 |
| `API.md` | 接口约定 | "code/data/message"统一封装与代码一致 | ✅ 保留 |
| `METRICS.md` | metric 定义(P0) | 后端 summary getter 一致 | ✅ 保留(轻量) |
| `INTEGRATION.md` | 整合策略 | spot-check 一致 | ⚠️ P2 评估(可能与 README 重复) |
| `API_HANDOFF.md` | 交接文档 | 标题"API 交接文档"+ 报告版本 1.9.0 与 `SimulationRunService.REPORT_VERSION` 一致 | ⚠️ **P2 候选删除**:典型的"开发过程交接文档" |
| `IMPLEMENTATION_SPLIT.md` | "Final Phase" 四人协作产出对应 | 历史阶段记录 | ⚠️ **P1 候选删除**:典型 historical 记录 |
| `FRONTEND_REFACTOR_REPORT.md` | 前端中文重构说明 | 一次性重构报告 | ⚠️ **P1 候选删除**:典型临时审查报告 |
| `源代码说明文档.md` | 子目录 / 文件用途说明 | 答辩 / 提交用 | ⚠️ P3 评估:与 ARCHITECTURE.md 部分重叠,但答辩需求或许必须 |

### 7.2 src_24281231/docs/

| 文件 | 内容 | 建议 |
|---|---|---|
| `docs/analysis/rfc-002-historical-diagnostics.md` | RFC-002 设计稿 | ⚠️ P2 评估:对应代码已落地(`HistoricalDiagnosticsService`) |
| `docs/analysis/rfc-003-historical-quality-score.md` | RFC-003 设计稿 | ⚠️ P2 评估:对应代码已落地(`HistoricalQualityScorer`) |

注意:**clean.txt 的 cleanup 范围是 git 仓库**(即 `src_24281231/`)。仓库根的 `D:\desktop\stu\software\docs\` 不在 git 内,本审计**不处理**。

### 7.3 跟踪的 IDE 文件

| 文件 | 跟踪? | 建议 |
|---|---|---|
| `.vscode/c_cpp_properties.json` | ✅ | P2 评估:跨成员 IDE 路径硬编码风险 |
| `.vscode/launch.json` | ✅ | 同上 |
| `.vscode/settings.json` | ✅(本地有 modified) | 同上 |
| `.editorconfig` | ✅ | 保留(跨编辑器一致) |
| `dataAnalyze/Project3.vcxproj.user` | ✅ | **P2 候选**:`*.vcxproj.user` 已在 .gitignore 但被先 commit |

---

## 8. 候选汇总(供 PLAN 引用)

### P0(无争议必删)

1. `target/` 全部 59 跟踪文件 — `git rm -r --cached target` + `rm -rf target`
2. `src/main/resources/static/frontend/` 全部 3 文件 — `git rm -r --cached` + 加 .gitignore
3. `src/main/resources/static/index.html` — `git rm --cached` + 加 .gitignore(同 build 产物)
4. 物理删除:`reports/`(777M)、`analysis-store/`、`sun/dist/`、`dataAnalyze/.vs/`、`dataAnalyze/Project3/x64/`
5. `dataAnalyze/Project3.vcxproj.user` — `git rm --cached`(.gitignore 已含)
6. .gitignore 微调:加 `/src/main/resources/static/frontend/`、`/src/main/resources/static/index.html`、(可选) `*.suo`

### P1(应删的废弃源码 / 文档)

- `IMPLEMENTATION_SPLIT.md` — 历史阶段记录,clean.txt §3.2 明确允许删
- `FRONTEND_REFACTOR_REPORT.md` — 临时审查报告,clean.txt 明确允许删

### P2(合并 / 收敛,本轮可选)

- `API_HANDOFF.md`:与 `API.md` 角色重叠,删除或合并
- `INTEGRATION.md`:与 `README.md` 部分重叠,合并或删
- `docs/analysis/rfc-00{2,3}-*.md`:RFC 设计稿,代码已落地
- `dataAnalyze/README.md`:与上一轮审计指出的 simulate 错误说明
- `.vscode/*.json`:IDE 私有配置(共享 vs 私有的边界)

### P3(暂缓,需用户裁决)

- **(分支 A)** Sync `SimulationOptimizationController` + `SimulationOptimizationController` 路径 + `OptimizationService` sync 用法 + `SimulationApiIntegrationTest` 中 2 个 sync 用例 — 等用户对"是否废弃同步 optimize 路径"裁决
- **(分支 B)** `samples/curated-history/`(81 文件)+ `CuratedSamplesGenerator.java` — 等用户对"是否仍需历史样本数据"裁决
- **(分支 C)** `dataAnalyze/DiningSimulation.{cpp,h}` — 上一轮已审计为"事实死代码",但跨 VS / CMake 工程拓扑;等用户裁决
- **(分支 D)** RFC 测试碎片化 (`Pr9b/c/d/e` 4 件) — 收益小,clean.txt §8.5 警告与本轮其他改动同 PR;不建议本轮做
- **(分支 E)** `源代码说明文档.md` — 答辩 / 交付物,需要保留还是与 ARCHITECTURE 合并

---

## 9. 红线对照(自检)

| clean.txt §3.2 红线 | 本审计是否触发? |
|---|---|
| #1 无引用分析的删除生产代码 | ❌ 全部候选都有 grep 证据 |
| #2 为旧测试保留废弃代码 | ❌ §4.5 把"sync optimize"问题摆给用户裁决,不擅自决定 |
| #3 继续新增功能 | ❌ |
| #4 重写核心仿真逻辑 | ❌ |
| #5 修改业务行为 | ❌(P3 暂缓的 Optimize sync 删除会触发,所以暂缓) |
| #6 把构建产物重新提交 | ❌ |
| #7 保留"可能以后有用" | §6 的 samples 已点出潜在违规,所以 P3 摆给用户 |
| #8 移到 legacy 假装清理 | ❌ |
| #9 用 TODO 代替删除 | ❌ |
| #10 文档继续描述已删功能 | 待 PLAN 阶段在文档清理时核查 |
| #11 一次性巨大改动 | 已分 PR-CLEAN-1..5 |
| #12 跳过最终测试 | 每 PR 后均跑测试 |
