# Implementation Split (Final Phase)

## 四人协作产出与最终系统的对应关系

| 成员 | 立项分工 | 最终交付位置 | 备注 |
|---|---|---|---|
| 成员 A（仿真引擎） | Java 后端核心 | `src/main/java/com/bjtu/simulation/engine/**` + `service/**` | 离散事件引擎、Agent profile、到达调度、快照记录器 |
| 成员 B（数据预处理 / 前端原型） | 早期 ECharts HTML 原型 | 设计语言已合入 `sun/`，原型归档至 `docs/legacy/canteen-viz-prototype/` | BJTU 蓝主色、卡片化、tab 切换、时间轴回放 |
| 成员 C（C++ 性能验证） | 高性能仿真验证 | 改造为 `dataAnalyze/canteen-analyze.exe` 统计后处理 CLI | Bootstrap CI / Gini 瓶颈分 / ANOVA |
| 成员 D（前端集成） | React 前端 | `sun/src/**` | Tailwind v3 + ECharts 5.5 重构后的统一前端 |

详见 `docs/analysis/adr/004-legacy-archival.md` 与 `005-tech-stack-deviation-from-spec.md`。

## P0 done in backend

1. Frozen API contract docs: `API.md`.
2. Frozen metric definitions: `METRICS.md`.
3. Unified response envelope: `code/message/data`.
4. `POST /api/simulation/run` returns full report payload (snake_case) directly.
5. `GET /api/simulation/report/latest` returns same report payload structure.
6. Input validation + normalization rules implemented (含 `MAX_DURATION_HOURS = 16.0`).
7. Core simulation invariants enforced after each event.
8. Seed-based deterministic simulation supported.
9. Minute-level timeline output added for frontend charting.
10. Spring DI 全面落地：11 个 Service `@Service` + Controller 构造器注入；`SimulationTaskService` `@PreDestroy shutdown()`。
11. C++ 后处理接入：`AnalysisController` + `ExternalAnalysisService`，binary 缺失优雅降级。
12. Baseline + integration tests added — 共 50 项全绿。

## Backend stable surface

1. Report history APIs: `GET /api/simulation/report/list` / `GET /api/simulation/report/{reportId}` / `GET /api/simulation/report/{reportId}/history`。
2. Configurable arrival distribution（Poisson / 课程峰 / 雨天峰）。
3. Analysis endpoints: `POST /api/analysis/run`、`POST /api/analysis/cross-scenario`。

## Frontend (sun)

1. 解析 `code/message/data` 响应包络。
2. KPI 卡（基于 `data.summary` 的 arrived/served/typical_wait/seat_utilization 等）。
3. ECharts 图表：TrendChart / QueueBarChart / SeatUtilizationLine / WaitDistributionBar。
4. 时间轴回放（保留 `selectedTimelineIndex / isPlaying / playSpeedMs` 状态）。
5. 场景对比 Tab：批量结果横向差分。
6. 高级统计面板：渲染 `/api/analysis/run` 返回的 CI / 瓶颈分 / ANOVA。

## C++ analysis subsystem

1. 三种 mode：`simulate`（保留向后兼容） / `analyze` / `batch-analyze`。
2. 退出码：0 success / 1 args / 2 missing input / 3 parse / 4 algorithm / 5 write / 6 unknown mode。
3. 算法实现：`AnalysisCore.{h,cpp}` 提供 `bootstrapCi` / `computeBottleneck` / `computeAnova`。
4. JSON 处理：自实现 `JsonUtil.h`，无外部依赖。

## Joint alignment checklist

1. Confirm all field names from `API.md` before UI binding.
2. Confirm metric formulas from `METRICS.md` before chart labels.
3. Confirm timeline granularity (minute) for all chart axes.
4. Confirm error handling flow using `code/message`（含 `code: 503` + `available: false` 的降级路径）。
