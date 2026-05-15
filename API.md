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
- `pack_probability` / `packProbability`：基础打包概率。
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

每个模型包含：

- `id`
- `name`
- `purpose`
- `category`
- `config`
- `expected_metrics`

### `POST /api/simulation/scenarios/run`

批量运行一个或多个预设模型。

```json
{
  "scenario_ids": [
    "baseline_offpeak",
    "lunch_peak_pressure"
  ],
  "overrides": {
    "seed": 20260610
  }
}
```

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
| `seat_utilization_rate` | number | 比例 | 占用座位秒 / 总座位秒 |
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
| `timeline` | array | - | 分钟级快照 |
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

由 `AnalysisController` 提供，内部通过 `ProcessBuilder` 调用 `dataAnalyze/bin/canteen-analyze.exe`，30 秒超时。binary 缺失或调用失败时返回 `code: 503`，`data` 退化为 `{ "available": false, "reason": "..." }`，**前端可据此优雅降级**。

### `POST /api/analysis/run`

```json
{ "report_id": "<simulation report id>" }
```

约束：`report_id` 必须通过 `SimulationReportRepository.isSafeReportId` 校验（仅字母、数字、下划线、横线），否则 `code: 400`。

成功响应 `data` 主要字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `available` | boolean | 是否成功调用 C++ binary |
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
| 503 | C++ binary 缺失或超时（30s）→ `available: false` |

详见 `docs/analysis/adr/002-cpp-as-postprocessor.md`。
