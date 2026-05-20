import { formatNumber, read } from '../utils/simulation'

function TimelinePlayer({
  timeline,
  selectedIndex,
  isPlaying,
  playSpeedMs,
  onSelect,
  onTogglePlay,
  onReset,
  onSpeedChange
}) {
  const points = Array.isArray(timeline) ? timeline : []
  const maxIndex = Math.max(0, points.length - 1)
  const index = Math.min(maxIndex, Math.max(0, selectedIndex || 0))
  const point = points[index] || {}
  const timeSeconds = read(point, 'time_seconds', 'timeSeconds', 'time') ?? index * 60
  const minute = Math.round(timeSeconds / 60)
  const queue = read(point, 'total_queue_size', 'totalQueueSize', 'queueing_student_count', 'queueingStudentCount') ?? 0
  const occupied = read(point, 'occupied_seats', 'occupiedSeats', 'dining_student_count', 'diningStudentCount') ?? 0
  const arrived = read(point, 'cumulative_arrived_count', 'cumulativeArrivedCount', 'arrived_count', 'arrivedCount') ?? 0
  const served = read(point, 'cumulative_served_count', 'cumulativeServedCount', 'served_count', 'servedCount') ?? 0

  if (!points.length) {
    return <div className="empty-state">暂无时间轴数据,请先运行仿真。</div>
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" className="btn-secondary" onClick={onReset}>↺ 回到起点</button>
        <button type="button" className="btn-primary" onClick={onTogglePlay}>
          {isPlaying ? '⏸ 暂停' : '▶ 播放'}
        </button>
        <label className="ml-auto flex items-center gap-2 text-sm text-slate-600">
          <span>速度</span>
          <select
            className="rounded-lg border border-canvas-border bg-canvas-surface px-2 py-1 text-sm"
            value={playSpeedMs}
            onChange={(event) => onSpeedChange(Number(event.target.value))}
          >
            <option value={1200}>慢速</option>
            <option value={700}>标准</option>
            <option value={350}>快速</option>
          </select>
        </label>
      </div>
      <input
        type="range"
        min="0"
        max={maxIndex}
        value={index}
        onChange={(event) => onSelect(Number(event.target.value))}
        className="timeline-thumb"
        aria-label="仿真时间轴"
      />
      <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-4 lg:grid-cols-6">
        <Stat label="帧" value={`${index + 1}/${points.length}`} />
        <Stat label="当前分钟" value={minute} />
        <Stat label="排队人数" value={queue} />
        <Stat label="占用座位" value={occupied} />
        <Stat label="累计到达" value={arrived} />
        <Stat label="累计服务" value={served} />
      </dl>
      <p className="text-xs text-slate-500">
        座位占用率 {formatNumber((read(point, 'seat_utilization_rate', 'seatUtilizationRate') ?? 0) * 100, 1)}%
      </p>
    </div>
  )
}

function Stat({ label, value }) {
  return (
    <div className="rounded-xl bg-canvas-base px-3 py-2">
      <dt className="text-[10px] uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="font-numeric text-base font-semibold tabular-nums text-bjtu-700">{value}</dd>
    </div>
  )
}

export default TimelinePlayer
