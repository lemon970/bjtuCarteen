# Metric Definitions (P0)

## Count metrics

- `arrivedCount`: all accepted arrival events.
- `normalArrivalCount` / `classPeakArrivalCount` / `rainPeakArrivalCount`: arrival-group split. Must satisfy:
- `normalArrivalCount + classPeakArrivalCount + rainPeakArrivalCount = arrivedCount`
- `abandonedCount`: arrivals that abandoned.
- `abandonedByQueueCount`: subset of `abandonedCount` caused by queue threshold.
- `servedCount`: service-finish count.
- `dineInCount`: served students who finally dine in.
- `takeawayCount`: served students who finally leave with takeaway.
- `pendingSeatDecisionCount`: served students who are in seat-search phase (transient runtime state).
- `noSeatSwitchToTakeawayCount`: subset of `takeawayCount` converted due to no seat.
- `weatherDrivenTakeawayCount`: subset of `takeawayCount` where weather factor changed decision.
- `leaveCount`: dine-in students who complete dining and leave.

Runtime invariant:

- `dineInCount + takeawayCount + pendingSeatDecisionCount = servedCount`
- `servedCount <= arrivedCount`
- `leaveCount <= servedCount`
- `abandonedByQueueCount <= abandonedCount`
- `noSeatSwitchToTakeawayCount <= takeawayCount`

Final report expectation:

- `pendingSeatDecisionCount = 0`

## Arrival model metrics

Default arrival model:

- target arrivals are derived from `round(arrivalRate * durationHours)`
- if `baseConfig.totalStudents > 0`, it is treated as an upper cap
- class-peak windows reshape the arrival timeline but do not change the target total

Class-peak arrival model:

- enabled by `peakConfig.classPeakEnabled = true`
- peak window defaults to minute `15-25`
- the per-minute arrival factor changes the distribution weight inside configured peak windows
- arrivals inside the configured peak window are counted as `classPeakArrivalCount`
- total arrival count remains bounded by the target arrival total

## Wait-time metrics

- wait time sample = `serviceStartTime - queueEnterTime`
- service time is tracked separately by the service distribution
- abandoned students are excluded
- `avgWaitTimeMinutes = totalWaitTimeMinutes / servedCount`
- `avgWaitTimeMinutes` is retained as the raw all-sample mean for compatibility
- `rawAvgWaitTimeMinutes`: same raw all-sample mean, explicitly named
- `steadyAvgWaitTimeMinutes`: mean over the middle 80% simulation window
- `typicalWaitTimeMinutes`: 10% trimmed mean over steady-state samples; use this for the primary student-experience KPI
- `medianWaitTimeMinutes`, `p75WaitTimeMinutes`, `p90WaitTimeMinutes`: weighted percentiles by `partySize`
- `longWaitRate`: share of served students waiting at least 10 minutes
- `zeroWaitRate`: share of served students with near-zero waiting
- `edgeWaitSampleRate`: share of warm-up and cool-down samples
- `waitTimeDistribution`: weighted buckets `0-2`, `2-5`, `5-10`, `10-15`, `15+`
- `waitTimeInsight`: status and Chinese explanation for queue, edge-sample, or long-tail issues

## Queue peak metrics

Two independent peaks are tracked:

- `maxQueueSize`: max single-window queue length
- `maxTotalQueueSize`: max sum of all windows

Associated fields:

- `peakTimeMinutes`: first minute when `maxQueueSize` appears (单窗口口径)
- `peakWindowId`: first window id that reached `maxQueueSize`
- `totalPeakTimeMinutes` / `total_peak_time_minutes`: first minute when `maxTotalQueueSize` appears (总队列口径，独立于 `peakTimeMinutes`，避免单窗与全窗峰值时间被混用)

## Seat metrics

- `occupiedSeats`: seats occupied at final state
- `emptySeats = max(0, totalSeats - occupiedSeats)`
- `avgOccupiedSeats`: sample mean over snapshots
- `maxOccupiedSeats`: max sampled occupied seats
- `seatUtilizationRate = avgOccupiedSeats / totalSeats` (0 when `totalSeats = 0`)

## Window throughput metrics

- `windowServedCounts[i]`: number of students finished service at window `i`
- invariant: `sum(windowServedCounts) = servedCount`

## Timeline metrics

`timeline` is minute-level replay data. Each point is intended to be a complete snapshot, so the front end can render a frame without deriving missing values.

- time: `timeSeconds`, `minute`, `eventMessage`
- window state: `windowQueueSizes`, `windowCount`, `totalQueueSize`, `queueingStudentCount`
- busiest window state: `busiestWindowId`, `busiestWindowQueueSize`
- seat state: `totalSeats`, `occupiedSeats`, `diningStudentCount`, `emptySeats`, `seatUtilizationRate`
- cumulative arrival counters: arrived/normal/classPeak/rainPeak
- cumulative outcome counters: abandoned/abandonedByQueue/served/dineIn/takeaway/pendingSeatDecision/noSeatSwitch/weatherDrivenTakeaway/leave

Snapshot aliases:

- `queueingStudentCount = totalQueueSize`
- `diningStudentCount = occupiedSeats`
- `seatUtilizationRate = occupiedSeats / totalSeats` (`0` when `totalSeats = 0`)

Sampling rule:

- each minute uses the latest snapshot within that minute
- if no snapshot in a minute, carry forward the previous state

## Time units

- internal event time: seconds
- summary end/peak fields: seconds or minutes as named
- timeline index: minute

## Dedicated takeaway window metrics

Dedicated takeaway windows are configured by `baseConfig.takeawayWindowCount`.

Definitions:

- `windowTypes[i] = NORMAL`: window `i` is a normal service window.
- `windowTypes[i] = TAKEAWAY`: window `i` is a dedicated takeaway window.
- `normalWindowCount = windowCount - takeawayWindowCount`.
- `takeawayWindowServedCount`: students whose service finished at dedicated takeaway windows.
- Students served at dedicated takeaway windows are counted as takeaway outcomes and do not occupy seats.
- `normalWindowQueueSize` and `takeawayWindowQueueSize` in timeline split queue pressure by window type.

Invariant:

- `normalWindowServedCount + takeawayWindowServedCount = servedCount`
- `takeawayWindowCount <= windowCount`

## Takeaway window time-cost analysis

Dedicated takeaway windows model a trade-off:

- They are slower than normal windows because packaging takes extra handling time.
- They reduce seat pressure because students served there leave with takeaway and do not occupy seats.

Configuration:

- `baseConfig.takeawayWindowCount`: number of dedicated takeaway windows.
- `baseConfig.takeawayServiceTimeMultiplier`: multiplier applied to service time at dedicated takeaway windows. Default is `1.15`.

Summary metrics:

- `takeawayRate = takeawayCount / servedCount`
- `dineInRate = dineInCount / servedCount`
- `takeawayWindowRatio = takeawayWindowCount / windowCount`
- `normalWindowServedRate = normalWindowServedCount / servedCount`
- `takeawayWindowServedRate = takeawayWindowServedCount / servedCount`

Interpretation:

- Higher `takeawayWindowRatio` may reduce `seatUtilizationRate` and dine-in pressure.
- Higher `takeawayServiceTimeMultiplier` may increase queue length and average waiting time.
- Compare `takeawayWindowRatio` with `takeawayWindowServedRate` to see whether takeaway windows are over-used or under-used.

## Dynamic feedback and overlapping peaks

Dynamic takeaway probability:

- `ServiceFinishEvent` now adds queue-pressure feedback to the takeaway decision.
- Feedback input is current total queue size after the student leaves the service queue.
- Feedback pressure is normalized by `windowCount * queueLimit` and capped before being converted into a probability bonus.

Overlapping class peaks:

- `peakConfig.classPeakWindows` can hold multiple class peak windows.
- Each window has `startMinute`, `endMinute`, and `multiplier`.
- When windows overlap, their extra pressure is added together, allowing scenarios such as 12:10 and 12:20 waves overlapping.

## Advanced statistics (C++ post-processing)

由 `dataAnalyze/canteen-analyze.exe` 生成,通过 `POST /api/analysis/run` 暴露给前端 `<AdvancedStatsPanel>`。这些字段不出现在 `simulation report` 自身,而是包装在分析响应的 `data` 对象中。

### Confidence intervals

`confidence_intervals.{wait, utilization, takeaway_rate}` 各自包含:

- `point`: 点估计值,与 summary 中对应字段一致
- `lower` / `upper`: Bootstrap 95% 置信区间下/上界(默认 1000 次重抽样,`alpha = 0.05`)
- `alpha`: 显著性水平,默认 `0.05`

### Bottleneck score

- `bottleneck_score`: 0~100 整数,综合反映窗口分配是否均衡 + 高峰是否持续
  - 公式:`50 * gini_of_window_queues + 50 * congested_minutes_ratio`
  - `gini_of_window_queues`:对 `windowServedCounts` 计算 Gini 系数(0=完全均衡,1=完全集中)
  - `congested_minutes_ratio`:`timeline` 中 `seatUtilizationRate >= 0.7` 的分钟数 / 总分钟数
- `bottleneck_breakdown`: `{ gini, congested_minutes, peak_window }` 拆解项,便于解释

### ANOVA(仅 cross-scenario)

- `anova.f_statistic` / `anova.p_value`:单因素方差分析结果
- `anova.significant`:`p_value < 0.05` 时为 `true`
- `anova.group_means`:每个场景的等待时间组均值

详见 `dataAnalyze/AnalysisCore.h` 与 ADR `002-cpp-as-postprocessor.md`。


