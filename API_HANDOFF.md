# 食堂仿真系统 API 交接文档

当前报告版本：`1.8.0`

本文档用于前端、C++ 数据分析成员与后端联调。所有接口默认返回 JSON。

传输优化约定：

- 后端已开启 HTTP 压缩，`application/json` 响应体超过 1024 字节时会自动压缩。
- Jackson 默认跳过 `null` 和空集合字段，但统一响应包络里的 `data: null` 会保留。
- 报告中的浮点型统计指标统一保留 3 位小数。
- `/run` 和 `/report/{id}` 默认不直接返回 `summary.history`，需要事件级明细时显式传 `include_history=true`。

## 1. 统一返回格式

成功：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

失败：

```json
{
  "code": 400,
  "message": "duration must be > 0",
  "data": null
}
```

错误码说明：

| code | HTTP 状态 | 含义 |
|---|---:|---|
| `0` | `200` | 成功 |
| `400` | `400` | 请求参数错误、JSON 格式错误、非法报告 ID |
| `404` | `404` | 报告不存在 |
| `500` | `500` | 后端内部错误或仿真状态不变量失败 |

## 2. 接口总览

| 方法 | 路径 | 用途 |
|---|---|---|
| `POST` | `/api/simulation/run` | 执行一次仿真，并直接返回完整报告 |
| `POST` | `/api/simulation/start` | `/run` 的别名，功能相同 |
| `POST` | `/api/simulation/run/async` | 异步提交仿真任务，立即返回任务状态 |
| `GET` | `/api/simulation/task/{id}/status` | 查询异步任务状态 |
| `GET` | `/api/simulation/task/{id}/stream` | 通过 SSE 订阅异步任务状态 |
| `POST` | `/api/simulation/optimize` | 兼容保留的多配置批处理对比接口，不做自动寻优 |
| `GET` | `/api/simulation/report/latest` | 获取最近一次仿真报告 |
| `GET` | `/api/simulation/report/list` | 获取历史报告列表摘要 |
| `GET` | `/api/simulation/report/{id}` | 按报告 ID 获取某一次完整报告 |
| `GET` | `/api/simulation/report/{id}/timeline` | 分页获取指定报告的分钟级时间轴 |
| `GET` | `/api/simulation/report/{id}/history` | 分页获取指定报告的事件级历史 |

## 3. POST /api/simulation/run

### 3.1 功能

提交一次仿真配置，后端执行离散事件仿真，返回本次完整报告，并把报告写入：

```text
reports/simulation-report-{timestamp}-{report_id}.json
reports/simulation-report-latest.json
```

查询参数：

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---:|---|
| `include_history` | boolean | `false` | 是否在响应中直接返回 `summary.history`；默认关闭，建议通过分页接口读取 history |

### 3.2 请求体字段

请求字段支持 `camelCase`，多数关键字段也支持 `snake_case`。

| 字段 | 类型 | 单位 | 默认值 | 规则 | 说明 |
|---|---|---:|---:|---|---|
| `simulationName` | string | 无 | `default-simulation` | 可选 | 仿真名称 |
| `duration` | number | 小时 | `1.0` | `> 0` | 仿真到达事件生成时长 |
| `arrivalRate` | number | 人/小时 | `60.0` | `>= 0` | 基础到达率 |
| `queueLimit` | integer | 人/窗口 | `10` | `>= 0` | 每个窗口排队容忍阈值 |
| `packProbability` | number | 概率 | `0.2` | `[0, 1]` | 全局基础打包概率 |
| `seed` | integer | 无 | 当前时间戳 | 可选 | 随机种子，用于复现实验 |
| `baseConfig.windowCount` | integer | 个 | `5` | `>= 1` | 窗口总数 |
| `baseConfig.takeawayWindowCount` | integer | 个 | `0` | `0 <= value <= windowCount` | 专门打包窗口数 |
| `baseConfig.takeawayServiceTimeMultiplier` | number | 倍率 | `1.15` | `>= 1.0` | 打包窗口服务耗时倍率 |
| `baseConfig.totalSeats` | integer | 个 | `50` | `>= 0` | 座位总数 |
| `baseConfig.totalStudents` | integer | 人 | `0` | `>= 0` | 学生数上限；`0` 表示按到达率推导 |
| `weatherConfig.currentWeather` | string | 无 | `unknown` | 可选 | 包含 `rain` 时归类为雨天场景 |
| `weatherConfig.weatherImpactFactor` | number | 倍率 | `1.0` | `>= 0` | 天气对打包概率的影响 |
| `randomBounds.arrivalInterval` | integer | 秒 | `0` | `>= 0` | 固定到达间隔；`0` 表示自动按到达率计算 |
| `randomBounds.serviceRange` | integer[2] | 秒 | `[60, 300]` | 自动归一化 | 普通窗口服务时间范围 |
| `randomBounds.diningRange` | integer[2] | 秒 | `[600, 1800]` | 自动归一化 | 堂食时间范围 |
| `randomBounds.preferenceRange` | number[2] | 概率 | `[0.1, 0.3]` | 运行时裁剪到 `[0, 1]` | 学生个体打包偏好生成范围 |
| `peakConfig.classPeakEnabled` | boolean | 无 | `false` | 可选 | 是否启用下课高峰模式 |
| `peakConfig.classPeakStartMinute` | integer | 分钟 | `15` | `>= 0` | 单波峰起点 |
| `peakConfig.classPeakEndMinute` | integer | 分钟 | `25` | `>= 0` | 单波峰终点 |
| `peakConfig.classPeakMultiplier` | number | 倍率 | `5.0` | `>= 1.0` | 单波峰最大倍率 |
| `peakConfig.classPeakWindows` | array | 无 | `[]` | 可选 | 多波峰配置；存在时优先于单波峰字段 |

### 3.3 classPeakWindows 字段

用于模拟多个下课潮重叠，例如 `12:10` 和 `12:20` 两个波峰叠加。

| 字段 | 类型 | 单位 | 规则 | 说明 |
|---|---|---:|---|---|
| `startMinute` | integer | 分钟 | `>= 0` | 波峰开始时间 |
| `endMinute` | integer | 分钟 | `>= 0` | 波峰结束时间 |
| `multiplier` | number | 倍率 | `>= 1.0` | 波峰倍率 |

重叠规则：多个波峰重叠时，后端会把各波峰的额外压力叠加。

### 3.4 snake_case 别名

| camelCase | snake_case |
|---|---|
| `simulationName` | `simulation_name` |
| `duration` | `duration_hours`, `duration_hour` |
| `arrivalRate` | `arrival_rate`, `arrival_rate_per_hour` |
| `queueLimit` | `queue_limit` |
| `packProbability` | `pack_probability` |
| `baseConfig` | `base_config` |
| `windowCount` | `window_count` |
| `takeawayWindowCount` | `takeaway_window_count` |
| `takeawayServiceTimeMultiplier` | `takeaway_service_time_multiplier` |
| `totalSeats` | `total_seats` |
| `totalStudents` | `total_students` |
| `weatherConfig` | `weather_config` |
| `currentWeather` | `current_weather` |
| `weatherImpactFactor` | `weather_impact_factor` |
| `randomBounds` | `random_bounds` |
| `arrivalInterval` | `arrival_interval`, `arrival_interval_seconds` |
| `serviceRange` | `service_range`, `service_range_seconds` |
| `diningRange` | `dining_range`, `dining_range_seconds` |
| `preferenceRange` | `preference_range` |
| `peakConfig` | `peak_config` |
| `classPeakEnabled` | `class_peak_enabled`, `class_peak_mode` |
| `classPeakStartMinute` | `class_peak_start_minute` |
| `classPeakEndMinute` | `class_peak_end_minute` |
| `classPeakMultiplier` | `class_peak_multiplier` |
| `classPeakWindows` | `class_peak_windows`, `peak_windows` |
| `startMinute` | `start_minute`, `class_peak_start_minute` |
| `endMinute` | `end_minute`, `class_peak_end_minute` |
| `multiplier` | `multiplier`, `class_peak_multiplier` |

### 3.5 请求示例

```json
{
  "simulationName": "demo-overlap-peak",
  "duration": 1.0,
  "arrivalRate": 180,
  "queueLimit": 8,
  "packProbability": 0.35,
  "seed": 2026042105,
  "baseConfig": {
    "windowCount": 4,
    "takeawayWindowCount": 1,
    "takeawayServiceTimeMultiplier": 1.15,
    "totalSeats": 180,
    "totalStudents": 0
  },
  "weatherConfig": {
    "currentWeather": "sunny",
    "weatherImpactFactor": 1.0
  },
  "randomBounds": {
    "arrivalInterval": 0,
    "serviceRange": [120, 300],
    "diningRange": [720, 1500],
    "preferenceRange": [0.15, 0.50]
  },
  "peakConfig": {
    "classPeakEnabled": true,
    "classPeakWindows": [
      {"startMinute": 10, "endMinute": 20, "multiplier": 4.0},
      {"startMinute": 18, "endMinute": 28, "multiplier": 5.0}
    ]
  }
}
```

## 4. 完整报告 data 结构

`POST /run`、`GET /report/latest`、`GET /report/{id}` 的 `data` 均为完整报告。

| 字段 | 类型 | 说明 |
|---|---|---|
| `report_version` | string | 报告结构版本，当前 `1.8.0` |
| `report_id` | string | 报告唯一 ID |
| `effective_seed` | integer | 实际使用的随机种子 |
| `config` | object | 本次仿真的归一化配置快照 |
| `summary` | object | 统计汇总、时间轴、事件历史 |
| `generated_at` | string | ISO 格式生成时间 |
| `generated_at_epoch_millis` | integer | 毫秒时间戳 |

## 5. 异步仿真任务

### 5.1 POST /api/simulation/run/async

提交仿真任务后立即返回，不等待完整仿真结束。适合大规模场景。

成功响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "task_id": "uuid",
    "report_id": "uuid",
    "status": "PENDING",
    "submitted_at_epoch_millis": 1770000000000,
    "started_at_epoch_millis": 0,
    "completed_at_epoch_millis": 0,
    "error_message": "",
    "report_available": false
  }
}
```

任务状态：

| 状态 | 说明 |
|---|---|
| `PENDING` | 已提交，等待后台执行 |
| `RUNNING` | 后台正在执行 |
| `COMPLETED` | 已完成，报告已写入 `reports/` |
| `FAILED` | 执行失败，可查看 `error_message` |

### 5.2 GET /api/simulation/task/{id}/status

返回指定任务的当前状态。任务完成后，`data.summary` 会包含完整 summary。

### 5.3 GET /api/simulation/task/{id}/stream

使用 Server-Sent Events 推送任务状态。事件名为 `status`，事件数据结构与状态查询接口的 `data` 一致。

## 6. 静态配置边界

当前版本已冻结为“一次配置、一次仿真、一次报告”的静态仿真模式。

- 后端不再解析或执行 `triggers`。
- 运行过程中不会动态新增窗口、修改窗口类型、修改打包概率或修改服务耗时分布。
- 如请求体仍携带 `triggers` 字段，后端会按未知字段忽略，报告配置和 `summary` 中不会返回该字段。

## 7. 多配置批处理对比

### 7.1 POST /api/simulation/optimize

该接口为兼容保留接口，当前只做多配置批处理对比，不生成候选配置、不排序、不返回推荐方案。

请求示例：

```json
{
  "objective": "minimize avg_wait_time_minutes",
  "configs": [
    {"simulationName": "baseline", "duration": 0.5, "arrivalRate": 120},
    {"simulationName": "more-windows", "duration": 0.5, "arrivalRate": 120, "baseConfig": {"windowCount": 6}}
  ]
}
```

响应示例：

```json
{
  "mode": "batch_compare",
  "deprecated_optimization": true,
  "objective": "minimize avg_wait_time_minutes",
  "evaluated_configs": 2,
  "results": [
    {
      "index": 1,
      "report_id": "...",
      "config": {},
      "summary": {},
      "objective_value": 1.25
    }
  ]
}
```

兼容说明：

- `config` 仍可作为单配置输入。
- `configs` 或 `scenarios` 用于传入多组显式配置。
- `constraints`、`top_n` 不再产生自动搜索或排序效果。
- `max_candidates` 仅作为批处理数量上限，默认最多处理 100 组配置。

## 8. summary 字段

| 字段 | 类型 | 单位 | 说明 |
|---|---|---:|---|
| `history` | array | 无 | 事件级快照，数据最细；`/run` 和 `/report/{id}` 默认不返回，需 `include_history=true` |
| `timeline` | array | 无 | 分钟级快照，推荐前端和 C++ 分析优先使用 |
| `arrived_count` | integer | 人 | 总到达人数 |
| `normal_arrival_count` | integer | 人 | 普通到达人数 |
| `class_peak_arrival_count` | integer | 人 | 下课高峰到达人数 |
| `rain_peak_arrival_count` | integer | 人 | 雨天高峰到达人数 |
| `abandoned_count` | integer | 人 | 总放弃人数 |
| `abandoned_by_queue_count` | integer | 人 | 因排队过长放弃人数 |
| `served_count` | integer | 人 | 完成取餐服务人数 |
| `dine_in_count` | integer | 人 | 堂食人数 |
| `takeaway_count` | integer | 人 | 打包人数 |
| `pending_seat_decision_count` | integer | 人 | 正在找座决策人数；最终报告通常为 `0` |
| `no_seat_switch_to_takeaway_count` | integer | 人 | 因无座转打包人数 |
| `weather_driven_takeaway_count` | integer | 人 | 天气因素导致打包人数 |
| `leave_count` | integer | 人 | 堂食后离开人数 |
| `avg_wait_time_minutes` | number | 分钟 | 平均等待时间 |
| `total_wait_time_minutes` | number | 分钟 | 总等待时间 |
| `peak_time_minutes` | integer | 分钟 | 单窗口队列峰值出现时间 |
| `peak_window_id` | integer | 窗口 ID | 单窗口峰值窗口；无峰值时为 `-1` |
| `max_queue_size` | integer | 人 | 单窗口最大队列长度 |
| `max_total_queue_size` | integer | 人 | 所有窗口总队列最大长度 |
| `avg_total_queue_size` | number | 人 | 平均总队列长度 |
| `max_occupied_seats` | integer | 个 | 最大占座数 |
| `avg_occupied_seats` | number | 个 | 平均占座数 |
| `seat_utilization_rate` | number | 比例 | 平均座位利用率 |
| `window_served_counts` | integer[] | 人 | 各窗口服务完成数 |
| `window_types` | string[] | 无 | 各窗口类型，值为 `NORMAL` 或 `TAKEAWAY` |
| `normal_window_count` | integer | 个 | 普通窗口数 |
| `takeaway_window_count` | integer | 个 | 打包窗口数 |
| `normal_window_served_count` | integer | 人 | 普通窗口服务完成数 |
| `takeaway_window_served_count` | integer | 人 | 打包窗口服务完成数 |
| `takeaway_rate` | number | 比例 | `takeaway_count / served_count` |
| `dine_in_rate` | number | 比例 | `dine_in_count / served_count` |
| `takeaway_window_ratio` | number | 比例 | `takeaway_window_count / window_count` |
| `normal_window_served_rate` | number | 比例 | `normal_window_served_count / served_count` |
| `takeaway_window_served_rate` | number | 比例 | `takeaway_window_served_count / served_count` |
| `simulation_end_time_seconds` | integer | 秒 | 仿真最终事件时间 |
| `simulation_end_time_minutes` | integer | 分钟 | 仿真最终事件时间 |
| `total_seats` | integer | 个 | 座位总数 |
| `occupied_seats` | integer | 个 | 最终占座数 |
| `empty_seats` | integer | 个 | 最终空座数 |

## 9. timeline[] 字段

`timeline` 是分钟级快照，适合画折线图、做 C++ 数据分析、做结果回放。

| 字段 | 类型 | 单位 | 说明 |
|---|---|---:|---|
| `time_seconds` | integer | 秒 | 该分钟内最后一个事件时间；空帧为 `minute * 60` |
| `minute` | integer | 分钟 | 当前分钟 |
| `window_queue_sizes` | integer[] | 人 | 各窗口排队人数 |
| `window_types` | string[] | 无 | 各窗口类型 |
| `window_count` | integer | 个 | 窗口总数 |
| `normal_window_count` | integer | 个 | 普通窗口数 |
| `takeaway_window_count` | integer | 个 | 打包窗口数 |
| `total_queue_size` | integer | 人 | 所有窗口总排队人数 |
| `normal_window_queue_size` | integer | 人 | 普通窗口总排队人数 |
| `takeaway_window_queue_size` | integer | 人 | 打包窗口总排队人数 |
| `queueing_student_count` | integer | 人 | 当前排队学生数，等于 `total_queue_size` |
| `busiest_window_id` | integer | 窗口 ID | 当前最忙窗口；无队列时为 `-1` |
| `busiest_window_queue_size` | integer | 人 | 当前最忙窗口队列长度 |
| `total_seats` | integer | 个 | 座位总数 |
| `occupied_seats` | integer | 个 | 当前占座数 |
| `dining_student_count` | integer | 人 | 当前正在用餐人数，等于 `occupied_seats` |
| `empty_seats` | integer | 个 | 当前空座数 |
| `seat_utilization_rate` | number | 比例 | 当前座位利用率 |
| `event_message` | string | 无 | 该分钟内最后一条事件描述 |
| `cumulative_arrived_count` | integer | 人 | 累计到达人数 |
| `cumulative_normal_arrival_count` | integer | 人 | 累计普通到达人数 |
| `cumulative_class_peak_arrival_count` | integer | 人 | 累计下课高峰到达人数 |
| `cumulative_rain_peak_arrival_count` | integer | 人 | 累计雨天高峰到达人数 |
| `cumulative_abandoned_count` | integer | 人 | 累计放弃人数 |
| `cumulative_abandoned_by_queue_count` | integer | 人 | 累计因排队放弃人数 |
| `cumulative_served_count` | integer | 人 | 累计服务完成人数 |
| `cumulative_dine_in_count` | integer | 人 | 累计堂食人数 |
| `cumulative_takeaway_count` | integer | 人 | 累计打包人数 |
| `cumulative_pending_seat_decision_count` | integer | 人 | 当前/累计找座决策中人数快照 |
| `cumulative_no_seat_switch_to_takeaway_count` | integer | 人 | 累计无座转打包人数 |
| `cumulative_weather_driven_takeaway_count` | integer | 人 | 累计天气影响打包人数 |
| `cumulative_leave_count` | integer | 人 | 累计离开人数 |

## 10. history[] 字段

`history` 是事件级快照，体积更大。C++ 如只做整体统计，优先用 `summary` 和 `timeline`。

| 字段 | 类型 | 单位 | 说明 |
|---|---|---:|---|
| `time` | integer | 秒 | 事件发生时间 |
| `queue_sizes` | integer[] | 人 | 各窗口排队人数 |
| `total_queue_size` | integer | 人 | 总排队人数 |
| `occupied_seats` | integer | 个 | 占座数 |
| `empty_seats` | integer | 个 | 空座数 |
| `event_message` | string | 无 | 事件说明 |
| `arrived_count` | integer | 人 | 累计到达人数 |
| `abandoned_count` | integer | 人 | 累计放弃人数 |
| `abandoned_by_queue_count` | integer | 人 | 累计因排队放弃人数 |
| `served_count` | integer | 人 | 累计服务完成人数 |
| `dine_in_count` | integer | 人 | 累计堂食人数 |
| `takeaway_count` | integer | 人 | 累计打包人数 |
| `pending_seat_decision_count` | integer | 人 | 当前找座决策中人数 |
| `no_seat_switch_to_takeaway_count` | integer | 人 | 累计无座转打包人数 |
| `weather_driven_takeaway_count` | integer | 人 | 累计天气影响打包人数 |
| `leave_count` | integer | 人 | 累计离开人数 |
| `normal_arrival_count` | integer | 人 | 累计普通到达人数 |
| `class_peak_arrival_count` | integer | 人 | 累计下课高峰到达人数 |
| `rain_peak_arrival_count` | integer | 人 | 累计雨天高峰到达人数 |

## 11. GET /api/simulation/report/list

### 8.1 功能

扫描 `reports/` 目录，返回历史报告摘要列表。

### 8.2 返回 data

| 字段 | 类型 | 说明 |
|---|---|---|
| `count` | integer | 历史报告数量 |
| `reports` | array | 历史报告摘要数组 |

### 8.3 reports[] 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `report_id` | string | 报告 ID |
| `report_version` | string | 报告版本 |
| `generated_at` | string | 生成时间 |
| `generated_at_epoch_millis` | integer | 生成时间戳 |
| `effective_seed` | integer | 随机种子 |
| `file_name` | string | 报告文件名 |
| `file_size_bytes` | integer | 文件大小 |
| `file_modified_epoch_millis` | integer | 文件修改时间 |
| `simulation_name` | string | 仿真名称 |
| `config_snapshot` | object | 配置快照 |
| `summary_snapshot` | object | 摘要指标 |

`summary_snapshot` 包含：

| 字段 | 说明 |
|---|---|
| `arrived_count` | 总到达人数 |
| `abandoned_count` | 总放弃人数 |
| `served_count` | 服务完成人数 |
| `dine_in_count` | 堂食人数 |
| `takeaway_count` | 打包人数 |
| `avg_wait_time_minutes` | 平均等待时间 |
| `max_queue_size` | 单窗口最大队列长度 |
| `max_total_queue_size` | 总队列最大长度 |
| `peak_time_minutes` | 峰值时间 |
| `peak_window_id` | 峰值窗口 |
| `seat_utilization_rate` | 座位利用率 |
| `normal_window_count` | 普通窗口数 |
| `takeaway_window_count` | 打包窗口数 |
| `takeaway_window_served_count` | 打包窗口服务人数 |
| `takeaway_rate` | 打包率 |
| `dine_in_rate` | 堂食率 |
| `takeaway_window_ratio` | 打包窗口占比 |
| `takeaway_window_served_rate` | 打包窗口服务占比 |

## 12. GET /api/simulation/report/{id}

按 `report_id` 返回完整报告。

查询参数：

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---:|---|
| `include_history` | boolean | `false` | 是否在响应中直接返回 `summary.history`；默认关闭，建议通过 `/history` 分页接口读取 |

非法 ID 返回：

```json
{
  "code": 400,
  "message": "invalid report id",
  "data": null
}
```

不存在返回：

```json
{
  "code": 404,
  "message": "report not found",
  "data": null
}
```

## 13. GET /api/simulation/report/{id}/timeline

分页返回指定报告的 `summary.timeline`。适合前端按需加载时间轴，避免一次性读取完整报告。

查询参数：

| 参数 | 类型 | 默认值 | 规则 | 说明 |
|---|---|---:|---|---|
| `page` | integer | `1` | `>= 1` | 页码，从 1 开始 |
| `page_size` | integer | `500` | `[1, 5000]` | 每页条数 |

成功响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "report_id": "uuid",
    "collection": "timeline",
    "page": 1,
    "page_size": 500,
    "total_items": 60,
    "total_pages": 1,
    "has_next": false,
    "has_previous": false,
    "items": []
  }
}
```

## 14. GET /api/simulation/report/{id}/history

分页返回指定报告的 `summary.history`。适合事件级复盘和调试分析。

查询参数、分页响应结构与 `/timeline` 一致，区别是：

```json
{
  "data": {
    "collection": "history",
    "items": []
  }
}
```

分页参数非法时返回：

```json
{
  "code": 400,
  "message": "page must be >= 1",
  "data": null
}
```

或：

```json
{
  "code": 400,
  "message": "page_size must be in [1, 5000]",
  "data": null
}
```

## 15. 统计口径说明

| 指标 | 口径 |
|---|---|
| 放弃人数 | 学生到达时，如果所有窗口达到全局队列阈值，或所选窗口超过个人忍耐度，则计入放弃 |
| 平均等待时间 | `服务完成时间 - 到达时间`，包含排队和服务，不包含堂食时间 |
| 打包率 | `takeaway_count / served_count` |
| 堂食率 | `dine_in_count / served_count` |
| 打包窗口占比 | `takeaway_window_count / window_count` |
| 打包窗口服务占比 | `takeaway_window_served_count / served_count` |
| 座位利用率 | 平均占座数除以总座位数 |
| 峰值队列 | 单个窗口的历史最大队列长度 |
| 总队列峰值 | 所有窗口队列长度之和的历史最大值 |

## 16. 对接建议

前端建议优先使用：

| 用途 | 字段 |
|---|---|
| 摘要卡片 | `summary.arrived_count`, `summary.abandoned_count`, `summary.avg_wait_time_minutes`, `summary.takeaway_rate`, `summary.seat_utilization_rate` |
| 排队折线图 | `summary.timeline[].minute`, `summary.timeline[].total_queue_size` |
| 各窗口压力 | `summary.timeline[].window_queue_sizes`, `summary.timeline[].window_types` |
| 座位变化 | `summary.timeline[].occupied_seats`, `summary.timeline[].empty_seats` |
| 打包窗口分析 | `summary.takeaway_window_ratio`, `summary.takeaway_window_served_rate`, `summary.takeaway_rate` |

C++ 数据分析建议优先使用：

| 用途 | 字段 |
|---|---|
| 批量统计 | `summary` |
| 时间序列分析 | `summary.timeline` |
| 事件级复盘 | `summary.history` |
| 历史报告对比 | `GET /api/simulation/report/list` 的 `summary_snapshot` |

