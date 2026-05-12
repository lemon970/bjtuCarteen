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

- arrivals are generated at a fixed interval derived from `arrivalRate`
- if `randomBounds.arrivalInterval > 0`, that explicit interval is used
- if `baseConfig.totalStudents > 0`, it caps the number of scheduled arrivals

Class-peak arrival model:

- enabled by `peakConfig.classPeakEnabled = true`
- peak window defaults to minute `15-25`
- the per-minute arrival factor grows exponentially from `1.0` to `peakConfig.classPeakMultiplier`
- arrivals inside the configured peak window are counted as `classPeakArrivalCount`
- this model is intended for stress testing and demonstration curves; it can produce more arrivals than `duration * arrivalRate` when `totalStudents = 0`

## Wait-time metrics

- per-student wait time = `serviceFinishTime - arrivalTime`
- includes queue + service phase
- abandoned students are excluded
- `avgWaitTimeMinutes = totalWaitTimeMinutes / servedCount`

## Queue peak metrics

Two independent peaks are tracked:

- `maxQueueSize`: max single-window queue length
- `maxTotalQueueSize`: max sum of all windows

Associated fields:

- `peakTimeMinutes`: first minute when `maxQueueSize` appears
- `peakWindowId`: first window id that reached `maxQueueSize`

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

