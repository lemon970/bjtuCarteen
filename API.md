# 食堂仿真系统 API 文档

版本：`1.8.0`

本文档按当前后端代码整理，可作为前端、C++ 统计模块、性能测试模块的对接依据。

## 1. 接口合理性结论

当前 API 设计整体是合理的，已经满足 A 角色的后端与基础前后端传参需求：

- `POST /api/simulation/run`：提交参数并直接返回本次完整报告，适合前端一次调用拿到结果。
- `GET /api/simulation/report/latest`：读取最近一次仿真结果，适合刷新页面后恢复结果。
- `GET /api/simulation/report/list`：读取历史报告摘要，适合前端/统计模块做历史记录列表。
- `GET /api/simulation/report/{id}`：读取指定历史报告完整 JSON，适合进一步分析或对比。

需要注意：

- 当前历史报告基于本地 `reports/` 目录扫描，不是数据库查询。
- `report/list` 当前没有分页，报告文件很多时性能会下降。
- `run/latest/report/{id}` 返回完整报告，包含 `history` 和 `timeline`，响应体可能较大。
- 如果性能同学只需要汇总指标，应优先使用 `report/list` 中的 `summary_snapshot`。

## 2. 通用约定

### 2.1 Base URL

```text
/api/simulation
```

本地默认访问地址：

```text
http://localhost:8080/api/simulation
```

### 2.2 Content-Type

请求 JSON：

```http
Content-Type: application/json
```

### 2.3 字段命名

请求体推荐使用 `camelCase`：

```json
{
  "arrivalRate": 120,
  "baseConfig": {
    "windowCount": 4
  }
}
```

请求体也兼容主要 `snake_case` 字段：

```json
{
  "arrival_rate": 120,
  "base_config": {
    "window_count": 4
  }
}
```

响应体统一使用 `snake_case`：

```json
{
  "report_id": "...",
  "effective_seed": 123,
  "summary": {
    "arrived_count": 120
  }
}
```

### 2.4 时间和单位

| 字段类型 | 单位 |
|---|---|
| 输入 `duration` | 小时 |
| 输入 `arrivalRate` | 人/小时 |
| 输入 `arrivalInterval` | 秒 |
| 输入 `serviceRange` | 秒 |
| 输入 `diningRange` | 秒 |
| `history.time` | 秒 |
| `timeline.time_seconds` | 秒 |
| `timeline.minute` | 分钟 |
| `summary.peak_time_minutes` | 分钟 |
| `summary.avg_wait_time_minutes` | 分钟 |
| `summary.total_wait_time_minutes` | 分钟 |
| `seat_utilization_rate` | 0 到 1 的比例 |

### 2.5 窗口编号

窗口 ID 从 `0` 开始。

例如 `window_queue_sizes = [3, 5, 1]` 表示：

| 窗口 ID | 排队人数 |
|---|---:|
| `0` | `3` |
| `1` | `5` |
| `2` | `1` |

### 2.6 统一响应结构

所有接口都返回：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

字段说明：

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | integer | `0` 表示成功，非 `0` 表示失败 |
| `message` | string | 成功为 `success`，失败为错误信息 |
| `data` | object/null | 成功时为数据，失败时为 `null` |

常见错误码：

| HTTP 状态码 | `code` | 场景 |
|---|---:|---|
| `400` | `400` | 请求参数非法、JSON 格式错误、报告 ID 非法 |
| `404` | `404` | 报告不存在 |
| `500` | `500` | 服务器内部错误、报告文件读取失败、仿真状态异常 |

## 3. 运行仿真

### 3.1 接口

```http
POST /api/simulation/run
```

兼容别名：

```http
POST /api/simulation/start
```

### 3.2 说明

提交一次仿真配置，后端执行完整离散事件仿真，并返回本次完整报告。

同时后端会把报告写入：

```text
reports/simulation-report-{timestamp}-{report_id}.json
reports/simulation-report-latest.json
```

### 3.3 请求字段

| 字段 | 类型 | 单位 | 默认值 | 校验/归一化 | 说明 |
|---|---|---|---|---|---|
| `simulationName` | string | 无 | `default-simulation` | 可选 | 仿真名称，仅作为元数据 |
| `duration` | number | 小时 | `1.0` | `> 0` | 仿真到达事件生成时长 |
| `arrivalRate` | number | 人/小时 | `60.0` | `>= 0` | 基础到达率 |
| `queueLimit` | integer | 人/窗口 | `10` | `>= 0` | 全局窗口排队容忍阈值 |
| `packProbability` | number | 概率 | `0.2` | `[0, 1]` | 全局打包倾向 |
| `seed` | integer(long) | 无 | 当前时间戳 | 可选 | 随机种子，用于复现实验 |
| `baseConfig.windowCount` | integer | 个 | `5` | `>= 1` | 食堂窗口数 |
| `baseConfig.takeawayWindowCount` | integer | count | `0` | `>= 0` and `<= windowCount` | dedicated takeaway window count |
| `baseConfig.takeawayServiceTimeMultiplier` | number | multiplier | `1.15` | `>= 1.0` | takeaway window service time multiplier; packaging adds extra service time |
| `baseConfig.totalSeats` | integer | 个 | `50` | `>= 0` | 食堂座位总数 |
| `baseConfig.totalStudents` | integer | 人 | `0` | `>= 0` | 大于 `0` 时作为学生总数上限 |
| `weatherConfig.currentWeather` | string | 无 | `unknown` | 可选 | 包含 `rain` 时触发雨天高峰归类 |
| `weatherConfig.weatherImpactFactor` | number | 倍率 | `1.0` | `< 0` 时修正为 `0` | 影响最终打包概率 |
| `randomBounds.arrivalInterval` | integer | 秒 | `0` | `>= 0` | 大于 `0` 时使用固定到达间隔 |
| `randomBounds.serviceRange` | integer[2] | 秒 | `[60, 300]` | 归一化为 `[min, max]` | 服务时间随机范围 |
| `randomBounds.diningRange` | integer[2] | 秒 | `[600, 1800]` | 归一化为 `[min, max]` | 堂食时间随机范围 |
| `randomBounds.preferenceRange` | number[2] | 概率 | `[0.1, 0.3]` | 裁剪到 `[0, 1]` | 学生个体打包偏好范围 |
| `peakConfig.classPeakEnabled` | boolean | 无 | `false` | 可选 | 是否启用下课高峰模型 |
| `peakConfig.classPeakStartMinute` | integer | 分钟 | `15` | `>= 0` | 下课高峰开始分钟 |
| `peakConfig.classPeakEndMinute` | integer | 分钟 | `25` | 小于开始时会修正 | 下课高峰结束分钟 |
| `peakConfig.classPeakMultiplier` | number | 倍率 | `5.0` | `< 1` 时修正为 `1.0` | 高峰结束时最大到达倍率 |

### 3.4 支持的 snake_case 输入别名

| camelCase | snake_case |
|---|---|
| `simulationName` | `simulation_name` |
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

### 3.5 普通模式到达规则

当 `peakConfig.classPeakEnabled = false` 时：

1. 若 `randomBounds.arrivalInterval > 0`，使用该值作为固定到达间隔。
2. 若 `arrivalInterval <= 0` 且 `arrivalRate > 0`，到达间隔为 `round(3600 / arrivalRate)` 秒。
3. 若 `arrivalInterval <= 0` 且 `arrivalRate <= 0`，到达间隔使用默认值 `60` 秒。
4. 若 `baseConfig.totalStudents > 0`，学生数量以该值为上限。
5. 若 `baseConfig.totalStudents <= 0`，学生数量由 `floor(duration * arrivalRate)` 推导。
6. 实际学生数量还会受仿真时长限制：`floor(durationSeconds / arrivalInterval)`。

### 3.6 下课高峰模式到达规则

当 `peakConfig.classPeakEnabled = true` 时：

1. 后端按分钟生成到达事件。
2. 基础每分钟到达人数为 `arrivalRate / 60`。
3. 在 `[classPeakStartMinute, classPeakEndMinute]` 内，到达倍率按指数形式从 `1.0` 增长到 `classPeakMultiplier`。
4. 高峰区间内到达学生会计入 `class_peak_arrival_count`。
5. 若 `baseConfig.totalStudents > 0`，它作为学生总数硬上限。
6. 若 `baseConfig.totalStudents = 0`，总人数由基础到达率和高峰曲线共同决定。

### 3.7 请求示例：普通模式

```json
{
  "simulationName": "normal-demo",
  "duration": 1.0,
  "arrivalRate": 120,
  "queueLimit": 10,
  "packProbability": 0.25,
  "seed": 20260421,
  "baseConfig": {
    "windowCount": 4,
    "takeawayWindowCount": 1,
    "totalSeats": 80,
    "totalStudents": 0
  },
  "weatherConfig": {
    "currentWeather": "sunny",
    "weatherImpactFactor": 1.0
  },
  "randomBounds": {
    "arrivalInterval": 0,
    "serviceRange": [60, 180],
    "diningRange": [600, 1200],
    "preferenceRange": [0.1, 0.35]
  },
  "peakConfig": {
    "classPeakEnabled": false,
    "classPeakStartMinute": 15,
    "classPeakEndMinute": 25,
    "classPeakMultiplier": 5.0
  }
}
```

### 3.8 请求示例：下课高峰模式

```json
{
  "simulationName": "class-peak-demo",
  "duration": 0.75,
  "arrivalRate": 180,
  "queueLimit": 8,
  "packProbability": 0.3,
  "seed": 20260421,
  "baseConfig": {
    "windowCount": 3,
    "takeawayWindowCount": 1,
    "totalSeats": 40,
    "totalStudents": 0
  },
  "weatherConfig": {
    "currentWeather": "sunny",
    "weatherImpactFactor": 1.0
  },
  "randomBounds": {
    "arrivalInterval": 0,
    "serviceRange": [90, 240],
    "diningRange": [600, 1500],
    "preferenceRange": [0.1, 0.4]
  },
  "peakConfig": {
    "classPeakEnabled": true,
    "classPeakStartMinute": 15,
    "classPeakEndMinute": 25,
    "classPeakMultiplier": 6.0
  }
}
```

### 3.9 成功响应

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "report_version": "1.8.0",
    "report_id": "uuid",
    "effective_seed": 20260421,
    "config": {},
    "summary": {},
    "generated_at": "2026-04-21T14:30:00",
    "generated_at_epoch_millis": 1770000000000
  }
}
```

### 3.10 常见失败响应

参数非法：

```json
{
  "code": 400,
  "message": "packProbability must be in [0, 1]",
  "data": null
}
```

JSON 格式错误：

```json
{
  "code": 400,
  "message": "malformed JSON request body",
  "data": null
}
```

## 4. 查询最近报告

### 4.1 接口

```http
GET /api/simulation/report/latest
```

### 4.2 说明

读取：

```text
reports/simulation-report-latest.json
```

### 4.3 成功响应

返回结构与 `POST /api/simulation/run` 的 `data` 一致。

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "report_version": "1.8.0",
    "report_id": "uuid",
    "effective_seed": 20260421,
    "config": {},
    "summary": {},
    "generated_at": "2026-04-21T14:30:00",
    "generated_at_epoch_millis": 1770000000000
  }
}
```

### 4.4 失败响应

```json
{
  "code": 404,
  "message": "latest report not found",
  "data": null
}
```

## 5. 查询历史报告列表

### 5.1 接口

```http
GET /api/simulation/report/list
```

### 5.2 说明

扫描 `reports/` 目录下的历史报告 JSON。

不包含：

```text
simulation-report-latest.json
```

### 5.3 成功响应

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "count": 1,
    "reports": [
      {
        "report_id": "uuid",
        "report_version": "1.8.0",
        "generated_at": "2026-04-21T14:30:00",
        "generated_at_epoch_millis": 1770000000000,
        "effective_seed": 20260421,
        "file_name": "simulation-report-20260421-143000-uuid.json",
        "file_size_bytes": 123456,
        "file_modified_epoch_millis": 1770000000000,
        "simulation_name": "class-peak-demo",
        "config_snapshot": {},
        "summary_snapshot": {
          "arrived_count": 120,
          "abandoned_count": 5,
          "served_count": 115,
          "dine_in_count": 80,
          "takeaway_count": 35,
          "avg_wait_time_minutes": 4.2,
          "max_queue_size": 12,
          "max_total_queue_size": 30,
          "peak_time_minutes": 18,
          "peak_window_id": 2,
          "seat_utilization_rate": 0.73
        }
      }
    ]
  }
}
```

### 5.4 字段说明

| 字段 | 类型 | 说明 |
|---|---|---|
| `count` | integer | 历史报告数量 |
| `reports` | array | 历史报告摘要数组 |
| `report_id` | string | 报告 ID，用于查询完整报告 |
| `report_version` | string | 报告版本 |
| `generated_at` | string | 报告生成时间 |
| `generated_at_epoch_millis` | integer(long) | 报告生成时间戳 |
| `effective_seed` | integer(long) | 本次仿真实际使用的随机种子 |
| `file_name` | string | 报告文件名 |
| `file_size_bytes` | integer(long) | 文件大小 |
| `file_modified_epoch_millis` | integer(long) | 文件修改时间戳 |
| `simulation_name` | string | 仿真名称 |
| `config_snapshot` | object | 配置快照 |
| `summary_snapshot` | object | 摘要指标快照 |

### 5.5 性能注意

`report/list` 当前通过文件系统扫描实现。

建议：

- 前端列表页使用该接口，不要用 `report/{id}` 批量读取全部报告。
- 性能测试时记录 `reports/` 文件数量，文件越多扫描越慢。
- 后续如果报告数量很多，应增加分页或数据库索引。

## 6. 查询指定历史报告

### 6.1 接口

```http
GET /api/simulation/report/{id}
```

### 6.2 路径参数

| 参数 | 类型 | 说明 |
|---|---|---|
| `id` | string | `report_id` |

`id` 只允许：

```text
A-Z a-z 0-9 . _ -
```

### 6.3 成功响应

返回完整报告，结构与 `POST /api/simulation/run` 的 `data` 一致。

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "report_version": "1.8.0",
    "report_id": "uuid",
    "effective_seed": 20260421,
    "config": {},
    "summary": {},
    "generated_at": "2026-04-21T14:30:00",
    "generated_at_epoch_millis": 1770000000000
  }
}
```

### 6.4 失败响应

报告 ID 非法：

```json
{
  "code": 400,
  "message": "invalid report id",
  "data": null
}
```

报告不存在：

```json
{
  "code": 404,
  "message": "report not found",
  "data": null
}
```

## 7. 完整报告结构

### 7.1 SimulationReport

| 字段 | 类型 | 单位 | 说明 |
|---|---|---|---|
| `report_version` | string | 无 | 报告结构版本，当前 `1.8.0` |
| `report_id` | string | 无 | 报告唯一 ID |
| `effective_seed` | integer(long) | 无 | 实际随机种子 |
| `config` | object | 无 | 归一化后的配置快照 |
| `summary` | object | 无 | 仿真统计摘要 |
| `generated_at` | string | 无 | 本地时间字符串 |
| `generated_at_epoch_millis` | integer(long) | 毫秒 | Unix 毫秒时间戳 |

### 7.2 config

响应中的 `config` 使用 `snake_case`：

| 字段 | 类型 | 单位 | 说明 |
|---|---|---|---|
| `simulation_name` | string | 无 | 仿真名称 |
| `duration` | number | 小时 | 仿真时长 |
| `arrival_rate` | number | 人/小时 | 基础到达率 |
| `queue_limit` | integer | 人/窗口 | 队列阈值 |
| `pack_probability` | number | 概率 | 全局打包概率 |
| `seed` | integer/null | 无 | 请求传入的随机种子 |
| `base_config.window_count` | integer | 个 | 窗口数 |
| `base_config.total_seats` | integer | 个 | 座位数 |
| `base_config.total_students` | integer | 人 | 学生数上限 |
| `weather_config.current_weather` | string | 无 | 天气 |
| `weather_config.weather_impact_factor` | number | 倍率 | 天气影响因子 |
| `random_bounds.arrival_interval` | integer | 秒 | 到达间隔 |
| `random_bounds.service_range` | integer[2] | 秒 | 服务时间范围 |
| `random_bounds.dining_range` | integer[2] | 秒 | 堂食时间范围 |
| `random_bounds.preference_range` | number[2] | 概率 | 个体打包偏好范围 |
| `peak_config.class_peak_enabled` | boolean | 无 | 是否启用下课高峰 |
| `peak_config.class_peak_start_minute` | integer | 分钟 | 高峰开始 |
| `peak_config.class_peak_end_minute` | integer | 分钟 | 高峰结束 |
| `peak_config.class_peak_multiplier` | number | 倍率 | 高峰倍率 |

### 7.3 summary

| 字段 | 类型 | 单位 | 说明 |
|---|---|---|---|
| `history` | array | 无 | 事件级快照 |
| `timeline` | array | 无 | 分钟级快照 |
| `arrived_count` | integer | 人 | 总到达人数 |
| `normal_arrival_count` | integer | 人 | 普通到达人数 |
| `class_peak_arrival_count` | integer | 人 | 下课高峰到达人数 |
| `rain_peak_arrival_count` | integer | 人 | 雨天高峰到达人数 |
| `abandoned_count` | integer | 人 | 总放弃人数 |
| `abandoned_by_queue_count` | integer | 人 | 因队列过长放弃人数 |
| `served_count` | integer | 人 | 完成取餐服务人数 |
| `dine_in_count` | integer | 人 | 堂食人数 |
| `takeaway_count` | integer | 人 | 打包人数 |
| `pending_seat_decision_count` | integer | 人 | 正在找座决策人数，最终报告通常为 `0` |
| `no_seat_switch_to_takeaway_count` | integer | 人 | 因无座转打包人数 |
| `weather_driven_takeaway_count` | integer | 人 | 天气影响导致打包人数 |
| `leave_count` | integer | 人 | 堂食结束离开人数 |
| `avg_wait_time_minutes` | number | 分钟 | 平均等待时间 |
| `total_wait_time_minutes` | number | 分钟 | 总等待时间 |
| `peak_time_minutes` | integer(long) | 分钟 | 单窗口队列峰值出现时间 |
| `peak_window_id` | integer | 窗口 ID | 单窗口峰值窗口，若无峰值为 `-1` |
| `max_queue_size` | integer | 人 | 单个窗口最大队列长度 |
| `max_total_queue_size` | integer | 人 | 所有窗口总队列最大长度 |
| `avg_total_queue_size` | number | 人 | 平均总队列长度 |
| `max_occupied_seats` | integer | 个 | 最大占座数 |
| `avg_occupied_seats` | number | 个 | 平均占座数 |
| `seat_utilization_rate` | number | 比例 | 平均座位利用率 |
| `window_served_counts` | integer[] | 人 | 各窗口服务完成数 |
| `simulation_end_time_seconds` | integer(long) | 秒 | 仿真结束时间 |
| `simulation_end_time_minutes` | integer(long) | 分钟 | 仿真结束时间 |
| `total_seats` | integer | 个 | 总座位数 |
| `occupied_seats` | integer | 个 | 最终占座数 |
| `empty_seats` | integer | 个 | 最终空座数 |

### 7.4 timeline[]

`timeline` 是分钟级快照，适合前端曲线、快照回放、C++ 数据分析。

| 字段 | 类型 | 单位 | 说明 |
|---|---|---|---|
| `time_seconds` | integer(long) | 秒 | 该分钟内最后一个事件时间；空帧为 `minute * 60` |
| `minute` | integer(long) | 分钟 | 时间轴分钟 |
| `window_queue_sizes` | integer[] | 人 | 各窗口排队人数 |
| `window_count` | integer | 个 | 窗口数量 |
| `total_queue_size` | integer | 人 | 所有窗口总排队人数 |
| `queueing_student_count` | integer | 人 | 当前排队学生数，等于 `total_queue_size` |
| `busiest_window_id` | integer | 窗口 ID | 当前最忙窗口，若无队列为 `-1` |
| `busiest_window_queue_size` | integer | 人 | 当前最忙窗口队列长度 |
| `total_seats` | integer | 个 | 总座位数 |
| `occupied_seats` | integer | 个 | 当前占座数 |
| `dining_student_count` | integer | 人 | 当前正在用餐人数，等于 `occupied_seats` |
| `empty_seats` | integer | 个 | 当前空座数 |
| `seat_utilization_rate` | number | 比例 | 当前座位利用率 |
| `event_message` | string | 无 | 该分钟内最后一条事件说明 |
| `cumulative_arrived_count` | integer | 人 | 累计到达人数 |
| `cumulative_normal_arrival_count` | integer | 人 | 累计普通到达人数 |
| `cumulative_class_peak_arrival_count` | integer | 人 | 累计下课高峰到达人数 |
| `cumulative_rain_peak_arrival_count` | integer | 人 | 累计雨天高峰到达人数 |
| `cumulative_abandoned_count` | integer | 人 | 累计放弃人数 |
| `cumulative_abandoned_by_queue_count` | integer | 人 | 累计因排队放弃人数 |
| `cumulative_served_count` | integer | 人 | 累计完成取餐人数 |
| `cumulative_dine_in_count` | integer | 人 | 累计堂食人数 |
| `cumulative_takeaway_count` | integer | 人 | 累计打包人数 |
| `cumulative_pending_seat_decision_count` | integer | 人 | 累计/当前找座决策人数快照 |
| `cumulative_no_seat_switch_to_takeaway_count` | integer | 人 | 累计无座转打包人数 |
| `cumulative_weather_driven_takeaway_count` | integer | 人 | 累计天气影响打包人数 |
| `cumulative_leave_count` | integer | 人 | 累计离开人数 |

### 7.5 history[]

`history` 是事件级快照，数据更细，体积也更大。

| 字段 | 类型 | 单位 | 说明 |
|---|---|---|---|
| `time` | integer(long) | 秒 | 事件发生时间 |
| `queue_sizes` | integer[] | 人 | 各窗口队列 |
| `total_queue_size` | integer | 人 | 总队列人数 |
| `occupied_seats` | integer | 个 | 占座数 |
| `empty_seats` | integer | 个 | 空座数 |
| `event_message` | string | 无 | 事件说明 |
| `arrived_count` | integer | 人 | 当时累计到达 |
| `abandoned_count` | integer | 人 | 当时累计放弃 |
| `abandoned_by_queue_count` | integer | 人 | 当时累计排队放弃 |
| `served_count` | integer | 人 | 当时累计服务完成 |
| `dine_in_count` | integer | 人 | 当时累计堂食 |
| `takeaway_count` | integer | 人 | 当时累计打包 |
| `pending_seat_decision_count` | integer | 人 | 当时找座决策中人数 |
| `no_seat_switch_to_takeaway_count` | integer | 人 | 当时累计无座转打包 |
| `weather_driven_takeaway_count` | integer | 人 | 当时累计天气影响打包 |
| `leave_count` | integer | 人 | 当时累计离开 |
| `normal_arrival_count` | integer | 人 | 当时累计普通到达 |
| `class_peak_arrival_count` | integer | 人 | 当时累计下课高峰到达 |
| `rain_peak_arrival_count` | integer | 人 | 当时累计雨天高峰到达 |

## 8. 对接建议

### 8.1 前端对接

- 参数提交使用 `POST /api/simulation/run`。
- 展示最近结果使用 `GET /api/simulation/report/latest`。
- 历史列表使用 `GET /api/simulation/report/list`。
- 查看历史详情使用 `GET /api/simulation/report/{id}`。
- 折线图优先使用 `summary.timeline`，不要从 `history` 自己重算。
- 页面卡片指标优先使用 `summary` 顶层字段。

### 8.2 C++ 统计模块对接

- 如果只需要摘要，读取 `report/list` 的 `summary_snapshot` 即可。
- 如果需要完整时间序列，读取 `report/{id}` 后使用 `summary.timeline`。
- 如果需要事件级分析，再读取 `summary.history`。
- `effective_seed`、`generated_at_epoch_millis`、`time_seconds` 建议使用 `int64_t`。
- 比例和平均值字段建议使用 `double`。
- 数组字段如 `window_queue_sizes`、`window_served_counts` 的下标就是窗口 ID。

### 8.3 性能测试对接

建议记录以下指标：

| 指标 | 来源字段 |
|---|---|
| 接口运行耗时 | 客户端压测工具统计 |
| 响应体大小 | HTTP 响应大小 |
| 到达人数 | `summary.arrived_count` |
| 放弃人数 | `summary.abandoned_count` |
| 服务完成人数 | `summary.served_count` |
| 最大单窗口队列 | `summary.max_queue_size` |
| 最大总队列 | `summary.max_total_queue_size` |
| 平均等待时间 | `summary.avg_wait_time_minutes` |
| 座位利用率 | `summary.seat_utilization_rate` |
| 时间轴长度 | `summary.timeline.length` |
| 事件历史长度 | `summary.history.length` |

建议性能场景：

| 场景 | 推荐参数 |
|---|---|
| 无人流 | `arrivalRate = 0` |
| 普通午餐 | `arrivalRate = 120`, `windowCount = 4`, `totalSeats = 80` |
| 高人流少窗口 | `arrivalRate = 420`, `windowCount = 2`, `queueLimit = 6` |
| 座位紧张 | `arrivalRate = 240`, `totalSeats = 5` |
| 下课波峰 | `classPeakEnabled = true`, `classPeakMultiplier = 6.0` |

### 8.4 报告体积注意

完整报告包含：

- `summary.history`
- `summary.timeline`
- `summary.config`

高人流或长时长仿真会产生较大 JSON。

如果只做列表展示，不要频繁调用 `report/{id}`。

如果后续需要长期压测，建议新增：

- 分页版 `report/list`
- 不含 `history` 的轻量版报告接口
- 批量对比接口

## 9. 当前未提供的接口

以下接口当前代码中没有实现：

- `GET /api/simulation/report/page`
- `GET /api/simulation/report/compare`
- `POST /api/simulation/batch`
- `DELETE /api/simulation/report/{id}`
- Swagger/OpenAPI 自动文档地址

如需这些能力，需要后续单独开发。

## 10. 打包窗口模型说明

打包窗口通过 `baseConfig.takeawayWindowCount` 配置。

当前约定：

- `baseConfig.windowCount` 表示窗口总数。
- `baseConfig.takeawayWindowCount` 表示其中有多少个是专门打包窗口。
- 打包窗口默认放在窗口编号末尾。
- 例如 `windowCount = 5` 且 `takeawayWindowCount = 2` 时，窗口 `0,1,2` 为普通窗口，窗口 `3,4` 为打包窗口。
- 学生选择窗口时会考虑自身打包偏好：`TAKEAWAY_BIASED` 学生更倾向打包窗口，`DINE_IN_BIASED` 学生更倾向普通窗口。
- 学生如果在打包窗口完成服务，会直接打包离开，不再参与堂食座位占用。
- `takeawayWindowCount` 必须大于等于 `0`，且不能超过 `windowCount`。

打包窗口相关输出字段：

| 字段 | 位置 | 说明 |
|---|---|---|
| `config.base_config.takeaway_window_count` | report | 本次配置的打包窗口数量 |
| `summary.window_types` | report | 每个窗口的类型数组 |
| `summary.normal_window_count` | report | 普通窗口数量 |
| `summary.takeaway_window_count` | report | 打包窗口数量 |
| `summary.normal_window_served_count` | report | 普通窗口服务完成数 |
| `summary.takeaway_window_served_count` | report | 打包窗口服务完成数，服务结果会直接计入打包 |
| `timeline[].window_types` | timeline | 每分钟快照中的窗口类型数组 |
| `timeline[].normal_window_queue_size` | timeline | 当前普通窗口总排队人数 |
| `timeline[].takeaway_window_queue_size` | timeline | 当前打包窗口总排队人数 |

### 10.1 Takeaway service-time and analysis fields

Added in report version `1.4.0`:

- `baseConfig.takeawayServiceTimeMultiplier`: service-time multiplier for dedicated takeaway windows. Default `1.15` means takeaway service takes about 15% longer than normal service because packaging needs extra time.
- Normal service time still comes from `randomBounds.serviceRange`.
- Dedicated takeaway service time = sampled normal service time * `takeawayServiceTimeMultiplier`, rounded to seconds.
- Students served by dedicated takeaway windows are counted as takeaway outcomes and do not occupy seats.

New output fields:

| Field | Location | Meaning |
|---|---|---|
| `config.base_config.takeaway_service_time_multiplier` | report | service-time multiplier used by this run |
| `summary.takeaway_rate` | report | `takeaway_count / served_count` |
| `summary.dine_in_rate` | report | `dine_in_count / served_count` |
| `summary.takeaway_window_ratio` | report | `takeaway_window_count / window_count` |
| `summary.normal_window_served_rate` | report | `normal_window_served_count / served_count` |
| `summary.takeaway_window_served_rate` | report | `takeaway_window_served_count / served_count` |

### 10.2 Report 1.5.0 model alignment fields

Added in report version `1.5.0`:

- Dynamic takeaway feedback is applied in `ServiceFinishEvent`: final takeaway decision probability includes a queue-pressure bonus based on current total queue length.
- Multi class-peak windows are accepted through `peakConfig.classPeakWindows` / `peak_config.class_peak_windows`.
- Each peak window item supports `startMinute`, `endMinute`, and `multiplier` with snake_case aliases `start_minute`, `end_minute`, and `multiplier`.
- Overlapping peak windows are additively combined as pressure increments, so two overlapping class waves can produce a stronger arrival burst.

Example:

```json
"peakConfig": {
  "classPeakEnabled": true,
  "classPeakWindows": [
    {"startMinute": 10, "endMinute": 20, "multiplier": 4.0},
    {"startMinute": 18, "endMinute": 28, "multiplier": 5.0}
  ]
}
```

