# RFC-010/011/012 后端补漏 + 前端展示设计(Spec)

**日期**:2026-05-24
**作者**:lemon + Claude(brainstorming session)
**状态**:Draft,待用户复核

---

## 0. Context

RFC-010A/B/C(批量 + 聚合 + 敏感度)、RFC-011(等待体验代理 + 公平性)、RFC-012(瓶颈诊断)三套能力的后端 service / calculator 已落地并测试绿。**当前缺口**:

- RFC-010A/B/C 仅落到 service 层,**没有 controller endpoint**,前端无法调用
- RFC-011 / RFC-012 字段已注入 `summary`,但**前端 0 引用**(`grep -rn` 验证 sun/src 全 0)
- 用户需要的"查漏补缺 + 前端展示"。

本 spec 设计 4 个独立 PR(A/B/C/D)落地这些缺口,**不修改**任何已落地的算法层。

---

## 1. 架构总览(Rev 2)

### 1.1 后端补漏(统一任务模型)

引入 `AnalysisTaskService`(单一类,支持 `BATCH_RUN` / `SENSITIVITY_ANALYSIS` 两类任务),不为 batch 与 sensitivity 各起一套。

新增 endpoint:

```
BatchRunController:
  POST /api/simulation/batch/run/async      # 提交批量任务
  GET  /api/simulation/batch/report/{id}    # 读已落盘 BatchRunReport

SensitivityController:
  POST /api/simulation/sensitivity/run/async
  GET  /api/simulation/sensitivity/report/{id}

AnalysisTaskController(共用):
  GET  /api/simulation/analysis-task/{id}/status   # V1 polling

# /stream SSE V1 不实现,service 层预留 Consumer<AnalysisTaskRecord> 接口
```

新 Repository:`BatchReportRepository`(`reports/batch/`)+ `SensitivityReportRepository`(`reports/sensitivity/`),atomic write,id 校验复用 `SimulationReportRepository.isSafeReportId`。

### 1.2 前端补漏

| 模块 | 内容 |
|---|---|
| `AnalysisPage` | +3 panel:`WaitExperienceProxyPanel`(RFC-011A)、`FairnessPanel`(RFC-011B)、`BottleneckDiagnosisPanel`(RFC-012,无雷达图) |
| 路由 | 新增 `#/batch` → `BatchScanPage`,双 Tab(批量 / 扫描) |
| 顶部导航 | 扩为 `输入 / 展示 / 分析 / 批量与扫描` |
| Hooks | `useAnalysisTaskPolling`(复用 `taskPoller`,不动 `useTaskPolling`) |
| 图表 | `MetricIntervalBarChart`(SVG 实现 mean+CI 区间柱)+ `MetricSensitivityCurve`(echarts) |

### 1.3 核心约束

- RFC-010A/B/C / RFC-011 / RFC-012 service / calculator **算法和签名 0 改**
- 11 个相关 DTO(BatchRunRequest/Report、AggregateMetrics、MetricStat、PerSeedMetric、SensitivityRequest/Report、AxisResult、MetricSensitivityCurve、ScanAxis、WhitelistedParam)0 改
- 前端 `useTaskPolling` / `taskPoller` / `asyncRunDecision` 0 改(已核实 `createTaskPoller` 在 `taskPoller.js:18` 已 export,无需补)
- 不引入新依赖(无 `pom.xml` 变更、无 npm 新依赖)

### 1.4 PR 拆分(4 个独立 PR)

| PR | 范围 | 依赖 | 测试增量 |
|---|---|---|---|
| **PR-A** | 前端 RFC-011/012 3 panel(纯展示,数据已在 `summary`) | 无 | +14 vitest |
| **PR-B** | 后端 `AnalysisTaskService` + Batch endpoints + `BatchReportRepository` | 无 | +30 单元/集成 |
| **PR-C** | 后端 Sensitivity endpoints + `SensitivityReportRepository` | PR-B(共享 task service) | +20 单元/集成 |
| **PR-D** | 前端 `BatchScanPage` + `useAnalysisTaskPolling` + 2 chart 组件 | PR-B + PR-C | +24 vitest |

每 PR 独立可绿、可单测、可回滚。PR-A 先落,无后端依赖,最快出可见效果。

---

## 2. 后端 endpoint + AnalysisTaskService 详细设计

### 2.1 任务状态机

```
PENDING → RUNNING → COMPLETED        (报告已落盘,可读)
                  → FAILED            (errorMessage 必填,reportId 字段保留但无文件)
```

不实现 CANCELLED,与现有 `SimulationTaskService` 保持一致。

### 2.2 `AnalysisTaskService` 执行器

```java
@Service
public class AnalysisTaskService {
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
        1, 1,                                    // single worker
        0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(8),             // queued <= 8
        new ThreadFactoryBuilder()
            .setNameFormat("analysis-task-%d")
            .setDaemon(false).build(),
        new ThreadPoolExecutor.AbortPolicy()    // 饱和 → RejectedExecutionException
    );
    // 容量:active(1) + queued(8) = 9。超过 → 503。

    private static final int MAX_TERMINATED_RECORDS = 200;
    private static final Duration RECORD_TTL = Duration.ofMinutes(30);
    // ConcurrentHashMap<taskId, AnalysisTaskRecord>:满或 TTL 触发"最旧已终结优先"驱逐
}
```

**单 worker 的理由**:
- 限制资源占用 — 仿真 CPU-bound,并行抢 CPU 无吞吐增益
- 避免报告文件爆炸 — 串行天然限制并发 IO 写 `reports/*.json` 速率
- 简化调试 — 串行下 task 失败原因可追溯

**饱和语义**:`executor.execute(task)` 抛 `RejectedExecutionException` → controller 转 503 `analysis task queue full`。`MAX_TERMINATED_RECORDS=200` 只控制 record 内存上限,**不**控制可投递的任务数。

### 2.3 ID 规则

| ID | 规则 |
|---|---|
| 后端生成 `report_id` | 必带类型前缀:batch → `batch-<uuid12>-<epoch10>`(总长 ≤ 30 字符)、sensitivity → `sens-<uuid12>-<epoch10>`(总长 ≤ 29 字符) |
| 用户传 `run_id` | 当作**后缀**附加,不裸用:最终 reportId = `batch-<run_id>` 或 `sens-<run_id>`。**`run_id` 自身长度 ≤ 58**(留 6 字符给 `batch-` / `sens-` 前缀),仍走 `isSafeReportId` 校验 |
| 后端生成 `task_id` | 同样带类型前缀:`batch-task-<uuid12>` / `sens-task-<uuid12>`(总长 ≤ 23 字符) |

`isSafeReportId` 沿用 `[A-Za-z0-9_-]{1,64}`。所有后端生成形式一次成型 ≤ 30 字符,远低于 64 字符上限;只有用户传 `run_id` 路径需校长度,controller 入参校验 `run_id.length() > 58 → 400`。

### 2.4 `AnalysisTaskSnapshot` JSON

```json
{
  "task_id": "batch-task-a1b2c3d4e5f6",
  "type": "batch_run",
  "report_id": "batch-a1b2c3d4-1748000000",
  "report_endpoint": "/api/simulation/batch/report/batch-a1b2c3d4-1748000000",
  "status": "PENDING",
  "submitted_at_epoch_millis": 1748000000000,
  "started_at_epoch_millis": 0,
  "completed_at_epoch_millis": 0,
  "error_message": "",
  "report_available": false
}
```

`report_endpoint` 由 backend 注入,前端拿到 `COMPLETED && report_available=true` 后直接 fetch,不需按 type 拼路径。snapshot **不内嵌报告体**,polling 带宽稳定。

### 2.5 Endpoint schema

#### `POST /api/simulation/batch/run/async`

请求(沿用已有 `BatchRunRequest`):
```json
{ "base_config": { /* SimConfig */ }, "seeds": [1, 2, 3], "run_id": "optional" }
```

校验(controller 入口,**不进 service**):
- `seeds == null || seeds.length == 0` → 400
- `seeds.length > 50` → 400 `seeds count exceeds 50`
- `run_id != null && !isSafeReportId(run_id)` → 400
- `run_id != null && run_id.length() > 58` → 400 `run_id too long`(留 6 字符给 `batch-` 前缀)

响应:
```
HTTP 202 Accepted
Content-Type: application/json
{ "code": 0, "message": "accepted", "data": <AnalysisTaskSnapshot> }
```

**注意**:HTTP 202 + `body.code=0` 与项目已有 `SimulationController` 异步路径一致。前端按 `code === 0` 判定成功,HTTP status 仅作传输层信号。

#### `POST /api/simulation/sensitivity/run/async`

请求(沿用已有 `SensitivityRequest`):
```json
{
  "base_config": { /* SimConfig */ },
  "seeds": [1, 2, 3],
  "axes": [{ "parameter": "ARRIVAL_RATE", "points": [60, 90, 120] }],
  "run_id": "optional"
}
```

校验:
- `seeds.length > 30` → 400
- `axes.size() > 5` → 400
- 任一 `axis.points.length > 11` → 400
- 重复 parameter → 400(已由 RFC-010C 内部校验承接)
- **`estimatedRuns = seeds.length × Σ axes[i].points.length`;`> 200` → 400** `estimated runs exceeds 200`
- `run_id != null && !isSafeReportId(run_id)` → 400
- `run_id != null && run_id.length() > 59` → 400 `run_id too long`(留 5 字符给 `sens-` 前缀)

V1 实例:30 seeds × Σ(11+11+11+11+11) = 1650 runs 远超上限 → 入参阶段直接 reject。可接受组合例:`10 × 20 = 200`、`5 × 40 = 200`、`20 × 10 = 200`。

#### `GET /api/simulation/analysis-task/{id}/status`

```
200 ApiResponse<AnalysisTaskSnapshot>
404 ApiResponse{ code:404, message:"task not found" }
```

V1 仅 polling。前端 `useAnalysisTaskPolling` 复用 `taskPoller`(分级 1s→2s→5s + 熔断)。`/stream` SSE V1 不实现,service 预留事件回调 hook,后续接 SSE 时只需补 controller。

#### `GET /api/simulation/batch/report/{id}` & `GET /api/simulation/sensitivity/report/{id}`

读 Repository 前 `isSafeReportId(id)` → 否则 400。文件不存在 → 404。成功返完整 `BatchRunReport` / `SensitivityReport`。

### 2.6 Repository 设计

```
BatchReportRepository:
  rootDir = reports/batch/                  (启动 Files.createDirectories)
  save(BatchRunReport report):
      Path tmp = rootDir.resolve(report.runId + ".json.tmp")
      mapper.writeValue(tmp.toFile(), report)
      Files.move(tmp, rootDir.resolve(report.runId + ".json"),
                 StandardCopyOption.ATOMIC_MOVE, REPLACE_EXISTING)
  read(String id):
      if (!isSafeReportId(id)) throw IllegalArgumentException
      Path p = rootDir.resolve(id + ".json")
      return Files.exists(p) ? Optional.of(mapper.readValue(...)) : Optional.empty()

SensitivityReportRepository:  对称接口,rootDir = reports/sensitivity/
```

**3 条规则**:
1. **atomic write**:save 先写 `.json.tmp`,再 `Files.move(ATOMIC_MOVE)`。crash 时 `.json` 永远完整。
2. **read 前 isSafeReportId 校验**:防 path traversal。Repository + controller 双重防线。
3. **不提供 list endpoint**:不实现 batch/sensitivity 报告列表。前端通过 task snapshot 得 reportId,无 list 需求。后续独立 RFC 再加。

### 2.7 已知技术债(per-seed report 落盘)

**事实**:`BatchRunService.run()` 内部串行调用 `SimulationRunService.run(clonedConfig, perSeedReportId)`,每次调用经 `SimulationReportRepository.save(...)` 落 1 份完整 `SimulationReport` 到 `reports/<perSeedReportId>.json`。`SensitivityAnalysisService` 同理(每个 axis × point × seed 触发一次)。

**影响**:
- N seed batch → 产出 N 个完整 `reports/*.json`(每份 50KB ~ 5MB)
- estimatedRuns=200 sensitivity → 产出 200 个 per-seed report 文件

**V1 控制手段**:`estimatedRuns ≤ 200` + `seeds ≤ 50` 入参上限就是为此设计的硬天花板。**不**新增 metric-only 路径(那是 RFC-010A 标注的 FULL_REPORTS_DEBUG 反向能力,已在 PR-BE-CLEAN-2 删除占位)。

**记录位置**:`BatchRunController` javadoc 引用 `[V1: per-seed reports persisted, see spec §2.7]`;后续若膨胀成问题,独立 RFC。

### 2.8 算法层 0 改声明

- `BatchRunService.run(BatchRunRequest)` 签名不动
- `SensitivityAnalysisService.run(SensitivityRequest)` 签名不动
- 11 个相关 DTO 0 改
- RFC-010A/B/C / RFC-011 / RFC-012 calculator 全 0 改

### 2.9 错误码

| code | 含义 |
|---|---|
| 0 | 成功(包括 HTTP 202 异步受理) |
| 400 | 入参校验失败(seeds/axes/estimatedRuns 越界 / 非法 run_id / 重复 parameter) |
| 404 | task not found / report not found |
| 503 | analysis task queue full(executor 拒绝),`message` 含队列状态 |

---

## 3. 前端 RFC-011/012 三 Panel 详细设计(PR-A)

### 3.1 共享 helper

直接复用已有 `sun/src/utils/simulation.js:3` `read(obj, ...keys)`(用 `!== undefined && !== null` 判断,**0 视为有效值**)。3 个 panel 都用 `read(summary, snake, camel)` 兼容 snake/camel,**不**新建 `readMetric`。

### 3.2 `WaitExperienceProxyPanel`

文件:`sun/src/components/WaitExperienceProxyPanel.jsx`,入参 `{ summary }`。

字段绑定:
| UI 标签 | 字段(snake_case) | 单位 |
|---|---|---|
| 综合代理指数 | `wait_experience_proxy_index` | 0~1 |
| P/I 比 | `pre_process_wait_share` | 0~1 |
| 不确定性 | `wait_uncertainty_score` | 0~1 |
| 焦虑压力 | `anxiety_pressure_index` | 0~1 |
| 独食调整等待 | `solo_adjusted_wait_minutes` | 分钟 |
| 样本数 | `sample_count` | 整数(party-weighted) |

空态:`summary.wait_experience_proxy_metrics == null`(后端 `<50` 样本时整对象为 null)→ 空态卡片 "样本不足(< 50),无法生成等待体验代理"。**不**显示 0 值。

固定文案(中文):
> 等权融合的启发式代理指标,仅用于同模型内相对比较,不解释为真实感知等待时间。

### 3.3 `FairnessPanel`

文件:`sun/src/components/FairnessPanel.jsx`,入参 `{ summary }`。

字段绑定:
| UI 标签 | 字段 | 单位 | 阈值 / 状态色 |
|---|---|---|---|
| 等待 GINI | `wait_gini` | 0~1 | `<0.20` 绿 / `0.20~0.40` 黄 / `≥0.40` 红 |
| 非打包窗口负载 CV | `non_takeaway_window_load_cv` | 比例 | `<0.20` 绿 / `0.20~0.30` 黄 / `≥0.30` 红 |
| 跨角色差异 | `cross_role_fairness` | 分钟 | `<3` 绿 / `3~6` 黄 / `≥6` 红 |
| 样本数 | `sample_count` | 整数 | 不上色 |

空态:`summary.fairness_metrics == null` → 同上空态文案。

固定文案(对照 `FairnessCalculator` 实际实现):
- Gini:等待时间分布的 Lorenz 曲线下方面积偏离对角线程度。0 = 完全公平,1 = 极端不公平。`(2 × Σ i*y_i) / (n × Σ y_i) - (n+1)/n`,party-weighted 样本展开。
- 非打包窗口负载 CV:stddev / mean(总体方差,N 为分母),对 `windowTypes != "TAKEAWAY"` 的所有窗口计算。注意:与 `window_choice_metrics.window_served_count_cv`(POPULAR+NORMAL+COLD 子集)是不同口径。
- 跨角色差异:solo dine-in / group dine-in / takeaway 三类的 party-weighted **中位数**等待时间 max − min(分钟)。每类 weighted 样本 < 5 跳过,可用类别 < 2 时返回 0。

### 3.4 `BottleneckDiagnosisPanel`

文件:`sun/src/components/BottleneckDiagnosisPanel.jsx`,入参 `{ summary }`。

**三条渲染分支**:

**(A) `summary.bottleneck_diagnosis == null` 防御路径**(理论上不会出现 — RFC-012 `BottleneckAnalyzer` 保证非 null,即使输入全空也回 BALANCED;此分支仅守后端意外回退):整个 panel 不渲染,与 RFC-011 两个 panel 的空态文案一致。

**(B) BALANCED 路径**(`bottleneck_diagnosis.primary == "balanced"`):绿色 banner "✓ 无明显瓶颈" + 单格绿色 severity-bar + 简短说明 "所有 4 类资源利用率均 < 0.85,系统处于均衡状态"。

**(C) 触发路径**:`primary` 卡片(红/橙/黄)+ optional `secondary` 卡片 + evidence 表(`type / severity / metric_name / observed_value / threshold / window_id`)。`bottlenecks[]` 数组按后端排序顺序直接渲染(severity desc + enum 序)。

**enum 大小写兼容**(防御后端意外回退):
```js
const primary = String(diagnosis.primary || '').toLowerCase()
const isBalanced = primary === 'balanced'
const styleFor = sev => SEVERITY_STYLE[String(sev || '').toLowerCase()] || SEVERITY_STYLE.balanced
```

**类型中文映射**:`window_service_capacity → 窗口服务能力`、`seat_capacity → 座位容量`、`takeaway_capacity → 打包窗口`、`arrival_surge → 到达冲击`、`balanced → 无瓶颈`。

**严重度色卡**:HIGH 红 / MEDIUM 橙 / LOW 黄 / BALANCED 绿。

**`windowId` 显示规则**(后端 0-based 已确认 `BottleneckAnalyzer:93,150` `maxIdx = i`):
```
windowId === -1  → "—"
windowId >= 0    → "窗口 #" + windowId        ← 直接显示后端值,不擅自 +1
```

雷达图本轮**不做**(决策保留)。

### 3.5 集成进 AnalysisPage

```
KPI 5 卡 → 结论摘要 → WaitTimePanel →
★ BottleneckDiagnosisPanel → ★ WaitExperienceProxyPanel → ★ FairnessPanel →
WindowChoiceMetricsCard → AdvancedStatsPanel → 打包决策表 → 参数复盘 → 趋势图
```

### 3.6 PR-A 测试增量

| 文件 | 单测数 |
|---|---|
| `WaitExperienceProxyPanel.test.jsx` | 3(完整 / 空态 / snake-camel 等价) |
| `FairnessPanel.test.jsx` | 4(完整 / 空态 / Gini 阈值上色 / cross-role 当前实现) |
| `BottleneckDiagnosisPanel.test.jsx` | 6(null 防御 / BALANCED / 单 / 双 / windowId=−1 / enum 大小写) |
| `AnalysisPage.test.jsx` | +1(3 panel 都被挂上) |

**合计 +14 vitest**。后端 0 改、0 测试增量。

---

## 4. 前端 BatchScanPage 详细设计(PR-D)

### 4.1 路由与布局

- 路由:`#/batch` → `BatchScanPage`,`App.jsx` 的 `currentHashPage` switch 加 case
- `AppLayout.jsx` 顶部导航加 "批量与扫描"
- 页面结构:Header + 双 Tab + Tab content + ResultArea
- Tab state 用 `useState('batch' | 'sensitivity')`,**两 tab 各持独立 taskId**
- 切 tab 不取消正在跑的 task(后端串行队列,前端只是不主动 poll)

### 4.2 BatchPanel 数据流

```
表单 → form state → 实时 estimatedRuns = seeds.length
                  → 提交按钮 disabled if seeds.length>50
提交 → POST /api/simulation/batch/run/async
     → 拿 task_id + report_id + report_endpoint → setActiveTaskId
useAnalysisTaskPolling(activeTaskId)
     → 1s/2s/5s 分级 poll status
     → COMPLETED && report_available → fetch(report_endpoint)
     → setBatchReport → 渲染 ResultArea
```

**表单字段**:
- `BaseConfigForm`(共享组件,简化版,6 个字段:duration / arrivalRate / windowCount / takeawayWindowCount / totalSeats / queueLimit)。**未填的 SimConfig 子结构**(如 `weatherFactor` / `groupConfig` / `distributionSpec` / `randomBounds` / `attractivenessConfig`)由前端 `buildBatchBaseConfig(simpleForm)` 用项目已有 `defaultSimConfig`(同 `InputPage` 默认)兜底,**不**在 BatchScanPage 暴露。SimulationConfigNormalizer 后端会再 fill 一遍空值,前端默认仅为 UX。
- `SeedsInput`:文本输入 `1,2,3` + 范围生成器按钮 + 随机生成器按钮
- 校验:解析失败 / 长度 0 / >50 → 红色提示 + 提交禁用

**ResultArea 完成后**:摘要卡(`run_id` / `base_config_digest` / `sample_count`)+ 11 行 `MetricIntervalBarChart` + "导出 JSON" 按钮(直接 fetch report_endpoint 落盘)。

### 4.3 SensitivityPanel 数据流

类似 batch,差异在配置区:
- 同 `BaseConfigForm`
- `SeedsInput` 上限 30
- `AxesInput`:动态行(最多 5 行),每行选 `parameter`(`WhitelistedParam` enum)+ `points`(逗号分隔,长度 ≤ 11)
- `estimatedRuns = seeds.length × Σ points.length`;`> 200` → 红色禁用提交

**ResultArea 完成后**:每条 axis 一个折线图(X = points,Y = 11 metric 切换器),mean line + CI band。

### 4.4 `estimatedRuns` 计算

`sun/src/utils/analysisRunEstimator.js`(纯函数):
```js
export function estimateBatchRuns(seedsArr) {
  return Array.isArray(seedsArr) ? seedsArr.length : 0
}
export function estimateSensitivityRuns(seedsArr, axes) {
  if (!Array.isArray(seedsArr) || !Array.isArray(axes)) return 0
  const sumPoints = axes.reduce((s, ax) => s + (Array.isArray(ax?.points) ? ax.points.length : 0), 0)
  return seedsArr.length * sumPoints
}
export const BATCH_LIMIT = 50
export const SENSITIVITY_LIMIT = 200
```

UI 三档颜色:`< limit*0.5` 绿、`< limit` 橙、`>= limit` 红 + 禁用提交。

### 4.5 `useAnalysisTaskPolling` Hook

文件:`sun/src/utils/useAnalysisTaskPolling.js`。**复用** `taskPoller.js:18` 已 export 的 `createTaskPoller(...)` 工厂函数,**不动** `useTaskPolling`、**不动** `taskPoller`。新 hook 内 `useEffect(() => createTaskPoller(taskId, fetchAnalysisTaskStatus, onUpdate), [taskId])`,unmount 调返回的 `stop()`。

### 4.6 `MetricIntervalBarChart`

文件:`sun/src/components/charts/MetricIntervalBarChart.jsx`。

入参:
```ts
{ rows: Array<{ label, mean, p10, p90, ci95Lower, ci95Upper, unit?, digits? }> }
```

V1 用 **SVG 实现**(`<div>` + Tailwind):每行横向 bar,bar 宽 = ci95 区间,中点 = mean。简单可靠。设计评审若需 echarts 风格再升级。

### 4.7 Sensitivity 曲线

文件:`sun/src/components/charts/MetricSensitivityCurve.jsx`。每个 axis 一个 echarts line chart,X=points / Y=mean line + CI band 阴影区,复用 `useEcharts`。

### 4.8 API client 扩展

`simulationApi.js` 加 4 个函数(沿用现有 fetch + ApiResponse 风格):
- `submitBatchRun(payload)` → POST batch/run/async
- `submitSensitivity(payload)` → POST sensitivity/run/async
- `fetchAnalysisTaskStatus(taskId)` → GET analysis-task/{id}/status
- `fetchAnalysisReport(endpoint)` → GET <report_endpoint>(snapshot 给好,前端不拼路径)

### 4.9 错误与边缘

| 场景 | 行为 |
|---|---|
| estimatedRuns 超限 | UI 阻止 + 红色提示;不发请求 |
| HTTP 503 队列满 | banner 显示 ApiResponse.message 中文化 |
| HTTP 400 校验错 | banner 显示后端 message |
| Polling 进 FAILED | banner 红色 + error_message,允许"重试"按钮 |
| Polling 硬超时(10 分钟) | banner 黄色提示后台仍在运行 |
| 报告 fetch 失败 | banner 红色,提供 reportId 给用户手动 curl |

### 4.10 PR-D 测试增量

| 文件 | 单测数 |
|---|---|
| `analysisRunEstimator.test.js` | 6 |
| `useAnalysisTaskPolling.test.jsx` | 4 |
| `MetricIntervalBarChart.test.jsx` | 3 |
| `MetricSensitivityCurve.test.jsx` | 2 |
| `BatchScanPage.test.jsx` | 5 |
| `simulationApi.test.js` | +4 |

**合计 +24 vitest**。

### 4.11 共享配置组件复用

提取轻量 `BaseConfigForm`(6 字段),不复用 InputPage 全表单。`InputPage` 不动。批量 / 敏感度视角不关心 weather/groupConfig/distributionSpec 等高级参数。

---

## 5. 测试策略 + 落地顺序 + 风险表 + 验收清单

### 5.1 测试基线累计

| PR | 后端 | 前端 | build:backend | 累计 |
|---|---|---|---|---|
| baseline | 415 | 62 | OK | 415 / 62 |
| **PR-A** | 0 | +14 | 必须 | 415 / **76** |
| **PR-B** | +30 | 0 | — | **445** / 76 |
| **PR-C** | +20 | 0 | — | **465** / 76 |
| **PR-D** | 0 | +24 | 必须 | 465 / **100** |

每 PR 硬门槛:`mvn -DskipFrontend=true test` 全绿 + `cd sun && npm test -- --run` 全绿 + `npm run build:backend` EXIT 0。任何一项红 → 不进下一 PR。

### 5.2 PR 测试覆盖关键场景

#### PR-B
- AnalysisTaskService:submit / 串行执行 / 队列饱和 503 / TTL+200 驱逐 / 状态机三态 / FAILED 路径
- BatchReportRepository:atomic write 中断模拟 / path traversal 拒绝 / Optional.empty
- BatchRunController:seeds=null/empty/51 → 400 / run_id 非法 → 400 / 正常路径 202+code=0+report_endpoint / GET report 完整流程

#### PR-C
- AnalysisTaskService 新增:SENSITIVITY_ANALYSIS dispatch
- SensitivityController:estimatedRuns=201 → 400 / =200 → 202 / axes.size=6 → 400 / points.length=12 → 400 / 重复 parameter → 400
- SensitivityReportRepository:对称用例

#### PR-A
- 各 panel:完整 / null 空态 / snake-camel 等价 / 阈值上色边界
- BottleneckDiagnosisPanel:enum 大小写鲁棒(BALANCED ≡ balanced)
- AnalysisPage 集成:3 panel 顺序正确

#### PR-D
- analysisRunEstimator 纯函数 6 用例
- useAnalysisTaskPolling:taskId 变化 / COMPLETED / FAILED / unmount cleanup
- BatchScanPage:tab 切换 / 提交 / running banner / 完成渲染 / 失败 banner

### 5.3 文档同步(每 PR 内)

| PR | API.md | USER_GUIDE.md | ARCHITECTURE.md |
|---|---|---|---|
| PR-A | — | "分析页:3 个新 panel" | — |
| PR-B | 新增 §"批量运行" + 4 endpoint + ApiResponse + 503 语义 | "多 seed 批量" | 数据流图加 AnalysisTaskService |
| PR-C | 新增 §"敏感度扫描" + estimatedRuns 上限 | "敏感度扫描" | — |
| PR-D | — | "批量与扫描页面入口" | — |

文档同步与代码同 PR(项目硬规则)。

### 5.4 风险表

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| 1 | AnalysisTaskService 内部 ConcurrentHashMap 状态竞态 | 中 | `compute` 原子化,record 字段 final / volatile;单 worker 减 race;CountDownLatch 测试 |
| 2 | BatchRunService 内部仍写 per-seed report,N=50 时落 50 份文件 | 中 | estimatedRuns ≤ 200 + seeds ≤ 50 硬封顶;§2.7 正面声明;后续 RFC 接 metric-only |
| 3 | estimatedRuns 公式前后端不一致 | 中 | 后端 testcase 复制到前端 vitest 等价比对 |
| 4 | atomic move 在 Windows 下被反病毒锁文件 | 低 | ATOMIC_MOVE 同卷支持;失败 fallback 普通 move + 警告 |
| 5 | RFC-011/012 sub-DTO null 路径线上未验证 | 低 | 单测显式构造 null;集成测试用 duration=0.05 触发 < 50 样本 |
| 6 | task_id / report_id 超 64 字符上限(`isSafeReportId` cap) | 低 | 后端生成形式 ≤ 30 字符;用户传 `run_id.length()` controller 入参校验 ≤ 58(batch)/ 59(sens);单测覆盖边界 |
| 7 | hash 路由切换导致 polling 中断 | 低 | useEffect 对 taskId 变化处理,unmount 调 stop |
| 8 | ServedFrontendBundleFreshnessTest 因 bundle 变化触发 | 低 | 每 PR 提交前必须 `npm run build:backend` |
| 9 | API.md 文档漂移 | 低 | 文档 + 代码同 PR commit |
| 10 | 用户改主意要走全分离 IA | 中 | spec 写完先复核(任务 #162),不直接进 writing-plans |

### 5.5 验收清单

实施完成后,以下全部 ✅ 才算交付:

- [ ] 4 个 PR 全部合入,基线 465 后端 / 100 前端测试全绿
- [ ] 4 个新 endpoint 可经 curl/Postman 验证(202/400/404/503 错误码全覆盖)
- [ ] `reports/batch/<run-id>.json` / `reports/sensitivity/<run-id>.json` 落盘正确
- [ ] AnalysisPage 浏览器看到 3 新 panel(BALANCED + 触发 + null 三路径手动验证)
- [ ] `#/batch` 浏览器看到双 Tab + estimatedRuns 计数器 + running banner + aggregate 结果
- [ ] BatchRunService / SensitivityAnalysisService / RFC-011 / RFC-012 calculator 文件 0 改动
- [ ] `pom.xml` 0 依赖变化
- [ ] API.md / USER_GUIDE.md 同步
- [ ] 第二轮清洗 §6 红线 10 项不踩

### 5.6 回滚策略

每 PR 独立分支(`feature/rfc010-12-pr-a/b/c/d`),每 PR 一个 commit(过大允许拆,便于 `git revert`)。出现红测立即 `git revert`,不 force push、不改 master 历史。PR-D 失败可单独回滚不影响 PR-A/B/C。PR-A 失败可单独回滚不影响后端 PR。

### 5.7 不做事项再确认

- 不实现 SSE `/stream`(留 RFC,service 预留 `Consumer<AnalysisTaskRecord>`)
- 不实现 batch / sensitivity report list endpoint
- 不实现 task cancel
- 不实现 BottleneckRadarChart
- 不引入 metric-only batch 路径(留独立 RFC)
- 不动 SimulationEngine、`@Service` 算法层、PREFERENCE_AWARE / RFC-009 / RFC-010A/B/C / RFC-011 / RFC-012 已落地代码

---

## 附录 A:关键代码位置参考

- 已落地后端:
  - `src/main/java/com/bjtu/simulation/service/BatchRunService.java`
  - `src/main/java/com/bjtu/simulation/service/SensitivityAnalysisService.java`
  - `src/main/java/com/bjtu/simulation/service/BottleneckAnalyzer.java`
  - `src/main/java/com/bjtu/simulation/service/WaitExperienceProxyCalculator.java`
  - `src/main/java/com/bjtu/simulation/service/FairnessCalculator.java`
- 已落地后端 DTO:`src/main/java/com/bjtu/simulation/dto/{BatchRunReport,SensitivityReport,AggregateMetrics,WaitExperienceProxyMetrics,FairnessMetrics,BottleneckDiagnosis,...}.java`
- 现有任务模式参考:`src/main/java/com/bjtu/simulation/service/SimulationTaskService.java`
- 前端复用基础:`sun/src/utils/{simulation,taskPoller,useTaskPolling}.js{x,}`
- 前端集成点:`sun/src/pages/{AnalysisPage,App}.jsx`、`sun/src/components/AppLayout.jsx`

## 附录 B:用户裁决记录(brainstorming session)

- 范围:**全包**(后端 endpoints + 前端 panel + 前端 batch 页)
- IA:**方案 B**(分析页加 panel,批量独立页)
- 调用模式:**异步 + polling**(SSE V1 不做)
- Seeds 上限:**Batch ≤ 50,Sensitivity estimatedRuns ≤ 200**
- 报告落盘:**落盘**(reports/batch/、reports/sensitivity/)
- RFC-011 布局:**两个完整 panel**
- Batch 结果密度:**只显 aggregate**
- RFC-012 形态:**文字 + 色条**(雷达图明确不做)
- Rev 2 修订(用户在 §1/§2/§3 提出):统一 task service、polling-only、PR 拆分细化、BottleneckRadarChart 不做、MetricBarErrorChart→MetricIntervalBarChart、ID 规则收紧、HTTP 202+code=0、estimatedRuns 控制、atomic write、列出 list endpoint 不做

---

**End of Spec.**
