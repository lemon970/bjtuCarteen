# canteen-analyze (C++ 后处理 CLI)

Java 后端跑出 `reports/*.json` 之后,这个程序读它们做统计后处理:bootstrap 置信区间、瓶颈打分(Gini)、多 seed 蒙特卡洛聚合、跨场景 ANOVA。是成员 C 在最终系统中的核心交付。

## 编译

### Visual Studio (推荐, 课程主要交付路径)

打开 `Project3.sln`,把 `源.cpp / AnalysisCore.cpp / DiningSimulation.cpp / AnalysisCore.h / JsonUtil.h / DiningSimulation.h` 全部加进项目,Release|x64 生成。把产物拷到 `bin/canteen-analyze.exe`。

### CMake (跨平台)

```bash
cd dataAnalyze
cmake -B build -S .
cmake --build build --config Release
# 产物位于 dataAnalyze/bin/canteen-analyze(.exe)
```

只需 C++17,无外部依赖(JSON 用本目录里 ~250 行的最小解析器 `JsonUtil.h`)。

## 子命令

```text
canteen-analyze --mode=simulate
canteen-analyze --mode=analyze --input=<报告.json> [--output=<结果.json>]
canteen-analyze --mode=batch-analyze --input-dir=<reports/> [--output=<跨场景.json>]
```

不指定 `--mode` 时退化为 `simulate` (兼容旧的演示行为)。`--output` 缺省时把 JSON 打到 stdout,`--output` 指向的目录会自动创建。

## 输出 schema

### `--mode=analyze` 输出

```json
{
  "schema_version": "1.0",
  "source_report_id": "abc-123",
  "confidence_intervals": {
    "wait_time_minutes": { "metric": "wait_time_minutes",
                            "mean": 4.6, "lower": 4.1, "upper": 5.0,
                            "sample_count": 380, "alpha": 0.05 }
  },
  "bottleneck": {
    "score": 41.7,
    "gini_coefficient": 0.32,
    "worst_window_id": 2,
    "sustained_peak_minutes": 12
  },
  "headline_metrics": {
    "typical_wait_time_minutes": 4.2,
    "seat_utilization_rate": 0.61,
    "takeaway_rate": 0.18,
    "served_count": 380,
    "abandoned_count": 9
  }
}
```

### `--mode=batch-analyze` 输出

```json
{
  "schema_version": "1.0",
  "report_count": 6,
  "monte_carlo": {
    "typical_wait_time_minutes": { "mean": 4.4, "stddev": 0.7, "min": 3.2, "max": 5.6, "report_count": 6 },
    "seat_utilization_rate": { "mean": 0.62, "stddev": 0.05, "min": 0.55, "max": 0.71, "report_count": 6 },
    "takeaway_rate": { ... },
    "arrived_count": { ... }
  },
  "anova": {
    "enabled": true,
    "f_statistic": 8.2,
    "between_group_variance": 12.4,
    "within_group_variance": 1.5,
    "group_count": 4,
    "total_samples": 1450,
    "strongest_group": "lunch_peak_pressure",
    "weakest_group": "baseline_offpeak"
  }
}
```

## 退出码

| 码 | 含义 |
|---|---|
| 0 | 成功 |
| 1 | 未识别的 `--mode` |
| 2 | 必填参数(`--input` / `--input-dir`)缺失 |
| 3 | 输入目录不存在 |
| 4 | 输出文件无法写入 |
| 5 | 解析输入 JSON 失败 |
| 6 | batch-analyze 输入文件少于 2 个 |

Java 端 `ExternalAnalysisService` 把 1~5 翻译为 HTTP 503,把二进制不存在翻译为 `available: false` 优雅降级。
