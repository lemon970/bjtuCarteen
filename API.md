# API 文档

统一返回格式：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

## 1. 单次仿真

### `POST /api/simulation/run`

运行一个 `SimConfig`，默认返回轻量报告，不包含完整 `history`。

常用字段：

- `duration`：仿真时长，单位小时。
- `arrival_rate` / `arrivalRate`：到达率，单位人/小时，是权威人数来源。
- `queue_limit` / `queueLimit`：单窗口排队阈值。
- `pack_probability` / `packProbability`:基础打包概率。运行时 `ServiceFinishEvent` 通过 `TakeawayDecisionPolicy.resolve` 把它与 `WeatherFactorPolicy` 解析出的天气有效因子、队列压力、座位压力共同加权得到 `finalProbability`,真正参与决策(详见 `METRICS.md` "Dynamic feedback")。
- `base_config.window_count`：窗口总数。
- `base_config.takeaway_window_count`：打包窗口数。
- `base_config.total_seats`：座位总数。
- `base_config.total_students`：人数上限。

## 2. 场景模型

### `GET /api/simulation/scenarios`

返回后端维护的预设模型目录。

内置 ID：

- `baseline_offpeak`
- `lunch_peak_pressure`
- `seat_shortage`
- `takeaway_intervention`
- `rain_emergency`
- `group_high_concentration`

每个模型包含：

- `id`
- `name`
- `purpose`
- `category`
- `config`
- `expected_metrics`

### `POST /api/simulation/scenarios/run`

批量运行一个或多个预设模型。

可选 query 参数:`include_history`(默认 `false`)。默认情况下批量响应会从每个 `summary` 中**剥除大字段**(`history` / `timeline` / `seat_cells` / `table_snapshots` / `arrival_samples` / `takeaway_decision_records` / `wait_time_metrics`),仅保留聚合数值指标(如 `arrived_count`、`takeaway_rate`、`typical_wait_time_minutes`),把 6 场景响应体从约 1MB+ 压到 100KB 内。需要完整时间轴时显式传 `?include_history=true`。

```json
{
  "scenario_ids": [
    "baseline_offpeak",
    "lunch_peak_pressure"
  ],
  "overrides": {
    "seed": 20260610,
    "group_config": { "enabled": true, "group_count": 20 }
  }
}
```

`overrides` 顶层键采用 snake_case(由全局 mapper 命名策略决定),其中 `group_config` 也会经 `ScenarioRunService.copyInto` 真实传入到达调度器。

响应 `data`：

- `scenario_count`
- `results[]`
  - `scenario_id`
  - `scenario_name`
  - `expected_metrics`
  - `report_id`
  - `config`
  - `summary`
- `comparison_summary`

## 3. 报告读取

### `GET /api/simulation/report/latest`

读取最新报告。

### `GET /api/simulation/report/list`

读取历史报告列表。列表项只包含摘要，不包含完整 history。

### `GET /api/simulation/report/{id}`

读取指定报告。默认不返回 `summary.history`。

可选参数：

- `include_history=true`：返回完整 history。

### `GET /api/simulation/report/{id}/history`

分页读取事件级 history。

参数：

- `page`：从 1 开始。
- `page_size`：1 到 5000。

### `GET /api/simulation/report/{id}/csv`

导出 CSV，包含到达样本、打包决策样本和历史快照。

## 4. 优化对比

### `POST /api/simulation/optimize`

当前是轻量批量对比接口，不是完整优化算法。旧目标 `avg_wait_time_minutes` 仍可用，推荐目标为：

```json
{
  "objective": "minimize typical_wait_time_minutes",
  "configs": []
}
```

> ⚠️ `constraints` 与 `topN` 字段已标 `@Deprecated`，保留兼容性但不参与逻辑。详见 `dto/OptimizationRequest.java` 的 Javadoc。

## 5. Summary 关键字段

| 字段 | 类型 | 单位 | 说明 |
|---|---|---:|---|
| `arrived_count` | integer | 人 | 总到达人数 |
| `served_count` | integer | 人 | 完成服务人数 |
| `dine_in_count` | integer | 人 | 堂食人数 |
| `takeaway_count` | integer | 人 | 打包人数 |
| `takeaway_rate` | number | 比例 | `takeaway_count / served_count` |
| `seat_utilization_rate` | number | 比例 | 兼容字段:运行期采样均值 / 总座位数(瞬时占用比) |
| `seat_time_weighted_utilization` | number | 比例 | **第八轮新增**·运营视角·占用座次秒 / (总座位 × 仿真总秒) |
| `peak_seat_utilization_rate` | number | 比例 | **第八轮新增**·`max_occupied_seats / total_seats` 峰值瞬时占用率 |
| `steady_state_seat_utilization` | number | 比例 | **第八轮新增**·剔除前 10% 与后 10% 帧后的均值,稳态负载 |
| `seat_turnover_rate` | number | 倍数 | **第八轮新增**·吞吐视角·`dine_in_count / total_seats`,翻台率,可大于 1 |
| `max_total_queue_size` | integer | 人 | 所有窗口总队列峰值 |
| `avg_wait_time_minutes` | number | 分钟 | 旧全量均值，兼容字段 |
| `raw_avg_wait_time_minutes` | number | 分钟 | 全量等待均值 |
| `steady_avg_wait_time_minutes` | number | 分钟 | 中间 80% 稳态样本均值 |
| `typical_wait_time_minutes` | number | 分钟 | 稳态样本 10% 截尾均值，推荐主 KPI |
| `median_wait_time_minutes` | number | 分钟 | P50 |
| `p75_wait_time_minutes` | number | 分钟 | P75 |
| `p90_wait_time_minutes` | number | 分钟 | P90 |
| `long_wait_rate` | number | 比例 | 等待不少于 10 分钟的占比 |
| `edge_wait_sample_rate` | number | 比例 | 开头和结尾阶段样本占比 |
| `wait_time_distribution` | array | - | 等待分桶 |
| `wait_time_insight` | object | - | 等待体验状态和归因 |
| `timeline` | array | - | 分钟级快照。每帧除已有 `seat_utilization_rate` 外,还包含 `seat_unavailable_rate` / `seat_reserved_share` / `seat_free_rate` / `reserved_seats`,以及 `frame_seat_layout[]`(每张桌子的紧凑结构,前端时间轴回放据此渲染成组占用色块)。`table_snapshots` 仍按既有策略剥除以控制响应大小。|
| `total_peak_time_minutes` | number | 分钟 | 总队列峰值出现的首个分钟（与 `peakTimeMinutes` 单窗口峰值口径区分）|

## 6. 等待体验模型

等待样本：

```text
wait = serviceStartTime - queueEnterTime
```

阶段划分：

- `WARMUP`：前 10% 仿真时间。
- `STEADY`：中间 80% 仿真时间。
- `COOLDOWN`：后 10% 仿真时间。

前端默认使用 `typical_wait_time_minutes`，避免开头零等待和结尾排空阶段拉偏全量均值。

参考范围：

- `0-5` 分钟：正常。
- `5-10` 分钟：轻度拥堵。
- `10-15` 分钟：明显拥堵。
- `15+` 分钟：严重排队。

## 7. 高级统计后处理（C++ 子系统）

由 `AnalysisController` 提供，内部通过 `ProcessBuilder` 调用 `dataAnalyze/bin/canteen-analyze.exe`，30 秒超时。

降级语义:
- **报告不存在**(`reportId` 在 `reports/` 目录中找不到)→ 返回 `code: 503`，`data = { "available": false, "reason": "report not found: ..." }`
- **C++ binary 缺失但报告存在** → 返回 `code: 0` + 由 `InternalStatisticsAnalyzer`(Java)计算的统计结果,响应中带 `source: "java_fallback"` 标记,**前端无需特殊处理**
- **C++ binary 调用失败 / 解析失败 / 超时** → 返回 `code: 503`,`data` 标记 `available: false`

### `POST /api/analysis/run`

```json
{ "report_id": "<simulation report id>" }
```

请求体兼容 snake_case 与 camelCase:`report_id` 与 `reportId` 二者任一即可(由 `RunRequest` 上的 `@JsonAlias("report_id")` 提供)。

约束:`report_id` 必须通过 `SimulationReportRepository.isSafeReportId` 校验(仅字母、数字、下划线、横线),否则 `code: 400`。

成功响应 `data` 主要字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `available` | boolean | 报告存在且分析可用(C++ 直接成功 或 Java fallback 成功)|
| `source` | string | `"cpp-canteen-analyze"`(C++ 路径)或 `"java_fallback"`(binary 缺失时由 `InternalStatisticsAnalyzer` 计算)|
| `report_id` | string | 回显报告 ID |
| `confidence_intervals.wait` | object | `{ point, lower, upper, alpha }` 等待时间 95% CI |
| `confidence_intervals.utilization` | object | 同上结构，座位利用率 95% CI |
| `confidence_intervals.takeaway_rate` | object | 同上结构，打包率 95% CI |
| `bottleneck_score` | number | 0~100，由窗口队列 Gini 系数 + 持续拥挤分钟数综合算出 |
| `bottleneck_breakdown` | object | `{ gini, congested_minutes, peak_window }` |

### `POST /api/analysis/cross-scenario`

```json
{ "scenario_ids": ["lunch_peak_pressure", "takeaway_intervention"] }
```

约束：至少 2 个场景，否则 `code: 400`。

成功响应 `data` 增加 `anova` 字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `anova.f_statistic` | number | 单因素 ANOVA F 值 |
| `anova.p_value` | number | 显著性水平 |
| `anova.significant` | boolean | `p < 0.05` |
| `anova.group_means` | object[] | 各场景的组均值 |

### 错误码

| code | 含义 |
|---|---|
| 0 | 成功 |
| 400 | 参数缺失 / 非法 report_id / 场景数不足 |
| 503 | 报告不存在 / C++ binary 调用失败 / 超时(30s)→ `available: false`(注:binary 缺失但报告存在时不再返回 503,会落到 Java fallback) |

详见 `docs/analysis/adr/002-cpp-as-postprocessor.md`。
