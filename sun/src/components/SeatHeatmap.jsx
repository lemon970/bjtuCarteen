import { buildSeatTables, formatPercent, read, summarizeGroupsOnFrame, summarizeSeatAreas, summarizeSeatCells, buildSeatCells } from '../utils/simulation'

const GROUP_RING_PALETTE = [
  'ring-rose-500',
  'ring-violet-500',
  'ring-emerald-500',
  'ring-sky-500',
  'ring-amber-500',
  'ring-fuchsia-500'
]

const GROUP_DOT_PALETTE = [
  'bg-rose-500',
  'bg-violet-500',
  'bg-emerald-500',
  'bg-sky-500',
  'bg-amber-500',
  'bg-fuchsia-500'
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

function groupColorIndex(groupId) {
  return hashGroupId(groupId) % GROUP_RING_PALETTE.length
}

function seatBgClass(status, groupId) {
  if (status === 'OCCUPIED') {
    return groupId ? 'bg-orange-600' : 'bg-slate-500'
  }
  if (status === 'RESERVED') {
    return groupId ? 'bg-orange-300' : 'bg-slate-300'
  }
  if (status === 'CLEANING') {
    return 'bg-amber-400'
  }
  return 'bg-slate-200'
}

function SeatHeatmap({ point, fallbackCells }) {
  const tables = buildSeatTables(point, fallbackCells)
  const flatCells = buildSeatCells(point, fallbackCells)
  const areas = summarizeSeatAreas(flatCells)
  const summary = summarizeSeatCells(flatCells)
  const groupsOnFrame = summarizeGroupsOnFrame(tables).filter((g) => g.seatCount >= 2)
  const occupiedIndividual = Math.max(0, summary.occupied - summary.grouped)
  const reservedFromTables = tables.reduce((acc, t) => acc + (t.reserved || 0), 0)
  const reservedTotal = Math.max(reservedFromTables, summary.reserved || 0)

  if (!tables.length) {
    return <div className="empty-state">暂无座位数据,请先运行仿真。</div>
  }

  const tablesByArea = new Map()
  for (const table of tables) {
    const area = table.area || 'A'
    const list = tablesByArea.get(area) || []
    list.push(table)
    tablesByArea.set(area, list)
  }
  const orderedAreas = [...tablesByArea.keys()].sort()
  const visibleGroups = groupsOnFrame.slice(0, 6)
  const overflowGroups = Math.max(0, groupsOnFrame.length - visibleGroups.length)

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2 text-xs">
        <SeatPill color="bg-orange-600" label="成组占用" value={summary.grouped} />
        <SeatPill color="bg-slate-500" label="个体占用" value={occupiedIndividual} />
        <SeatPill color="bg-orange-300" label="已预定" value={reservedTotal} />
        <SeatPill color="bg-amber-400" label="待清理" value={summary.cleaning} />
        <SeatPill color="bg-slate-200" label="空闲" value={summary.free} />
      </div>

      {visibleGroups.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5 text-[11px] text-slate-500">
          <span className="font-medium text-slate-600">本帧成组:</span>
          {visibleGroups.map((group) => {
            const idx = groupColorIndex(group.groupId)
            return (
              <span
                key={group.groupId}
                title={`组 ${group.groupId} / ${group.seatCount} 人 / 桌号 ${group.tableIds.join(',')}`}
                className="flex items-center gap-1 rounded-full bg-canvas-base px-1.5 py-0.5"
              >
                <span className={`h-2 w-2 rounded-sm ${GROUP_DOT_PALETTE[idx]}`} />
                <span>{group.seatCount}</span>
                <span className="text-slate-400">{group.tableCount === 1 ? '同桌' : `跨${group.tableCount}桌`}</span>
              </span>
            )
          })}
          {overflowGroups > 0 && (
            <span className="rounded-full bg-canvas-base px-1.5 py-0.5">+{overflowGroups}</span>
          )}
        </div>
      )}

      <div className="max-h-60 overflow-y-auto rounded-2xl bg-canvas-base p-2">
        {orderedAreas.map((area) => {
          const areaTables = tablesByArea.get(area)
          return (
            <div key={area} className="mb-2 last:mb-0">
              <div className="mb-1 flex items-center gap-2 text-[11px] text-slate-500">
                <span className="font-medium">{area} 区</span>
                <span className="text-slate-400">{areaTables.length} 桌</span>
              </div>
              <div className="flex flex-wrap gap-1">
                {areaTables.map((table) => (
                  <TableMicro key={table.tableId} table={table} />
                ))}
              </div>
            </div>
          )
        })}
      </div>

      <div className="space-y-1.5">
        {areas.map((area) => (
          <div key={area.area} className="flex items-center gap-3 text-xs">
            <span className="w-10 font-medium text-slate-600">{area.area} 区</span>
            <div className="progress-bar flex-1">
              <div className="progress-bar-fill" style={{ width: `${Math.max(3, area.utilization * 100)}%` }} />
            </div>
            <strong className="font-numeric w-12 text-right tabular-nums text-bjtu-700">{formatPercent(area.utilization)}</strong>
          </div>
        ))}
      </div>
    </div>
  )
}

function TableMicro({ table }) {
  const tableLabel = `T${String(table.tableId).padStart(2, '0')}`
  const occCount = table.occupied
  const reservedCount = table.reserved || 0
  const cap = Math.max(1, table.capacity || table.seats.length || 1)
  const tableTitle = `${tableLabel} · ${occCount}/${cap}` + (reservedCount > 0 ? ` (预定 ${reservedCount})` : '')
  return (
    <div className="flex gap-px" title={tableTitle}>
      {table.seats.map((seat, index) => {
        const status = seat.status || (seat.occupied ? 'OCCUPIED' : 'FREE')
        const bg = seatBgClass(status, seat.groupId)
        const ring = seat.groupId ? `ring-1 ring-inset ${GROUP_RING_PALETTE[groupColorIndex(seat.groupId)]}` : ''
        const stateText = status === 'OCCUPIED' ? '占用'
          : status === 'RESERVED' ? '已预定'
          : status === 'CLEANING' ? '清理中'
          : '空闲'
        const tooltip = `${tableLabel} 座${index + 1} / ${stateText}${seat.groupId ? ` / 组 ${seat.groupId}` : ''}`
        return (
          <span
            key={seat.seatId ?? index}
            title={tooltip}
            className={`h-1 w-1 rounded-[1px] ${bg} ${ring}`}
          />
        )
      })}
    </div>
  )
}

function SeatPill({ color, label, value }) {
  return (
    <span className="flex items-center gap-1.5 rounded-full bg-canvas-base px-2.5 py-1 text-slate-600">
      <span className={`h-2.5 w-2.5 rounded-sm ${color}`} />
      <span>{label}</span>
      <strong className="font-numeric tabular-nums text-bjtu-700">{value}</strong>
    </span>
  )
}

export default SeatHeatmap
