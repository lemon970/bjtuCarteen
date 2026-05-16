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

### 基础占用指标(向后兼容,瞬时占用比)

- `occupiedSeats`:当前仿真终态的占用座位数
- `emptySeats = max(0, totalSeats - occupiedSeats)`
- `avgOccupiedSeats`:运行期采样均值
- `maxOccupiedSeats`:运行期采样最大值
- `seatUtilizationRate = avgOccupiedSeats / totalSeats`(`totalSeats = 0` 时为 0)— 反映"平均瞬时占用比",对热身/排空敏感

### 第八轮:分层占用指标(按视角分组)

按需选用,而非用 `seatUtilizationRate` 一个数解释一切。

**学生视角 — 找不找得到座(基于每帧)**:

- `seat_unavailable_rate = (occupiedSeats + reservedSeats) / totalSeats`
  - 包含已落座 + 已预定但未落座的座位,代表"目前实际可坐的座位比例"
- `seat_reserved_share = reservedSeats / totalSeats`
  - 在途/正在落座的占比,可观察反向 → 大量预定但少量落座意味着寻位耗时长
- `seat_free_rate = 1 - seat_unavailable_rate`
  - 直观的"剩余可用座位比例"
- 不变量:`seat_unavailable_rate ≥ seat_utilization_rate`(因 reserved 加在分子上)

**运营视角 — 整体利用是否充分(基于全程)**:

- `seatTimeWeightedUtilization = Σ(table.occupiedSeatSeconds) / (totalSeats × simulationEndTimeSeconds)`
  - 直接由 `TableSnapshot.occupiedSeatSeconds` 累加,**真实的时间加权占用率**,不会被采样间隔与帧数偏好影响
- `peakSeatUtilizationRate = maxOccupiedSeats / totalSeats`
  - 整次仿真出现的最高瞬时占用率
- `steadyStateSeatUtilization`
  - 排除前 10% 与后 10% 的 timeline 帧后的均值,代表稳态(午峰中段)负载;实现位于 `SimulationRunService.computeSteadyStateUtilization`

**吞吐视角 — 每个座位接待了多少人**:

- `seatTurnoverRate = dineInCount / totalSeats`
  - 翻台率,可大于 1。`5.0` 意味着仿真期间每个座位平均接待 5 人次

### 选用建议

- 评估"高峰期座位是否够用":看 `peakSeatUtilizationRate` + `seat_unavailable_rate`(峰值帧)
- 评估"全天座位资源是否合理":看 `seatTimeWeightedUtilization`
- 评估"运营效率":看 `seatTurnoverRate`
- 与历史报告/旧前端对照:沿用 `seatUtilizationRate`,但需明白它只是"采样均值"

## Window throughput metrics

- `windowServedCounts[i]`: number of students finished service at window `i`
- invariant: `sum(windowServedCounts) = servedCount`

## Timeline metrics

`timeline` is minute-level replay data. Each point is intended to be a complete snapshot, so the front end can render a frame without deriving missing values.

- time: `timeSeconds`, `minute`, `eventMessage`
- window state: `windowQueueSizes`, `windowCount`, `totalQueueSize`, `queueingStudentCount`
- busiest window state: `busiestWindowId`, `busiestWindowQueueSize`
- seat state: `totalSeats`, `occupiedSeats`, `diningStudentCount`, `emptySeats`, `seatUtilizationRate`(瞬时占用比), `reservedSeats`, `seatUnavailableRate`, `seatReservedShare`, `seatFreeRate`
- per-frame seat layout: `frameSeatLayout[]` — 第八轮新增,每张桌子的紧凑结构(`tableId` / `capacity` / `occupiedSeats` / `reservedSeats` / `occupiedGroupIds` / `reservedGroupIds`),前端时间轴回放据此渲染成组占用色块。完整的 `tableSnapshots` 仍按既有策略剥除以控制响应大小。
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

- `ServiceFinishEvent` 在每次服务完成时调用 `TakeawayDecisionPolicy.resolve(...)` 计算 `finalProbability`,然后:
  - 若学生 `initialTakeawayIntent=true`(到达时已倾向打包,通常受天气驱动),`roll = 0.0`,直接判定为打包,`decisionReason="arrival takeaway intent retained; normal window serves takeaway"`
  - 若 `initialTakeawayIntent=false`(dine-in 倾向),`roll = engine.nextDouble()`,当 `roll < finalProbability` 时翻转为打包,`decisionReason="dynamic probability roll selected takeaway"`,并自动取消已预约的座位(`cancelReservationIfAny`),不污染座位统计
- `finalProbability = clamp(packProbability × weatherEffectiveFactor + queuePressureBonus + seatPressureBonus, 0, 1)`
  - `weatherEffectiveFactor = WeatherFactorPolicy.resolveEffectiveFactor(weatherType, userFactor)`,典型基线 sunny=1.00 / rainy=1.30 / stormy=1.55,与用户给出的 `weather_impact_factor` 相乘,clamp 到 [0.5, 3.0]
  - `queuePressureBonus`:当前总队长 / `(windowCount × queueLimit)` 归一化后转概率
  - `seatPressureBonus`:座位占用率高时加权
- 因此 `pack_probability`(配置)是**基础概率**,**运行时由 `TakeawayDecisionPolicy` 重算并真实参与决策**,而非仅记录用字段

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


