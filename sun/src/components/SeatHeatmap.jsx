import { buildSeatCells, formatPercent, read, summarizeSeatAreas, summarizeSeatCells } from '../utils/simulation'

const GROUP_RING_PALETTE = [
  'ring-accent-amber',
  'ring-bjtu-400',
  'ring-semantic-critical',
  'ring-semantic-success',
  'ring-semantic-warning',
  'ring-bjtu-700'
]

function hashGroupId(groupId) {
  if (!groupId) return 0
  let hash = 0
  const text = String(groupId)
  for (let i = 0; i < text.length; i++) {
    hash = (hash * 31 + text.charCodeAt(i)) >>> 0
  }
  return hash
}

function seatColorClass(status, groupId) {
  if (status === 'cleaning') return 'bg-accent-amber'
  if (status === 'free') return 'bg-canvas-border'
  if (status === 'occupied') {
    return groupId ? 'bg-accent-teal' : 'bg-bjtu-600'
  }
  return 'bg-canvas-border'
}

function SeatHeatmap({ point, fallbackCells }) {
  const cells = buildSeatCells(point, fallbackCells)
  const areas = summarizeSeatAreas(cells)
  const summary = summarizeSeatCells(cells)
  const totalSeats = read(point, 'total_seats', 'totalSeats') || cells.length
  const sampled = cells.some((cell) => read(cell, 'sampled') === true)
  const occupiedIndividual = Math.max(0, summary.occupied - summary.grouped)

  if (!cells.length) {
    return <div className="empty-state">暂无座位数据,请先运行仿真。</div>
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2 text-xs">
        <SeatPill color="bg-bjtu-600" label="个体占用" value={occupiedIndividual} />
        <SeatPill color="bg-accent-teal" label="成组占用" value={summary.grouped} />
        <SeatPill color="bg-accent-amber" label="待清理" value={summary.cleaning} />
        <SeatPill color="bg-canvas-border" label="空闲" value={summary.free} />
      </div>

      <div
        className="grid gap-1.5 rounded-2xl bg-canvas-base p-3"
        style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(0.875rem, 1fr))' }}
        aria-label="座位占用图"
      >
        {cells.map((cell, index) => {
          const status = String(read(cell, 'status') || 'FREE').toLowerCase()
          const groupId = read(cell, 'group_id', 'groupId') || ''
          const seatId = read(cell, 'seat_id', 'seatId') ?? index
          const colorClass = seatColorClass(status, groupId)
          const ringClass = groupId
            ? `ring-2 ${GROUP_RING_PALETTE[hashGroupId(groupId) % GROUP_RING_PALETTE.length]}`
            : ''
          return (
            <span
              key={seatId}
              title={`区域 ${read(cell, 'area') || 'A'} / 座位 ${index + 1} / ${statusLabel(status)}${groupId ? ` / 组 ${groupId}` : ''}`}
              className={`block h-3.5 w-3.5 rounded-sm ${colorClass} ${ringClass}`}
            />
          )
        })}
      </div>

      <div className="flex flex-wrap gap-3 text-xs text-slate-500">
        <Legend color="bg-bjtu-600" label="个体学生" />
        <Legend color="bg-accent-teal" label="成组学生(青底)" />
        <Legend color="bg-accent-amber" label="待清理:90s 清理窗口" />
        <Legend color="bg-canvas-border" label="空闲:可再分配" />
        <span>同组学生 ring 颜色一致,不同组使用不同色环。</span>
        {sampled && <span>已按 {cells.length}/{totalSeats} 抽样渲染</span>}
      </div>

      <div className="space-y-2">
        {areas.map((area) => (
          <div key={area.area} className="flex items-center gap-3">
            <span className="w-12 text-sm font-medium text-slate-600">{area.area} 区</span>
            <div className="progress-bar flex-1">
              <div className="progress-bar-fill" style={{ width: `${Math.max(3, area.utilization * 100)}%` }} />
            </div>
            <strong className="font-numeric w-14 text-right tabular-nums text-bjtu-700">{formatPercent(area.utilization)}</strong>
          </div>
        ))}
      </div>
    </div>
  )
}

function SeatPill({ color, label, value }) {
  return (
    <span className="flex items-center gap-1.5 rounded-full bg-canvas-base px-3 py-1 text-slate-600">
      <span className={`h-2.5 w-2.5 rounded-sm ${color}`} />
      <span>{label}</span>
      <strong className="font-numeric tabular-nums text-bjtu-700">{value}</strong>
    </span>
  )
}

function Legend({ color, label }) {
  return (
    <span className="flex items-center gap-1.5">
      <span className={`h-2.5 w-2.5 rounded-sm ${color}`} />
      <span>{label}</span>
    </span>
  )
}

function statusLabel(status) {
  if (status === 'occupied') return '占用'
  if (status === 'cleaning') return '待清理'
  return '空闲'
}

export default SeatHeatmap
