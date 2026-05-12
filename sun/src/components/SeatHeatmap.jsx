import { buildSeatCells, formatPercent, read, summarizeSeatAreas } from '../utils/simulation'

function SeatHeatmap({ point, fallbackCells }) {
  const cells = buildSeatCells(point, fallbackCells)
  const areas = summarizeSeatAreas(cells)

  if (!cells.length) {
    return <div className="empty-state">暂无座位数据，请先运行仿真。</div>
  }

  return (
    <div className="seat-heatmap">
      <div className="seat-grid" aria-label="座位占用热力图">
        {cells.map((cell, index) => {
          const status = String(read(cell, 'status') || 'FREE').toLowerCase()
          return (
            <span
              key={read(cell, 'seat_id', 'seatId') ?? index}
              className={`seat-cell ${status}`}
              title={`区域 ${read(cell, 'area') || 'A'} / 座位 ${index + 1} / ${statusLabel(status)}`}
            />
          )
        })}
      </div>

      <div className="seat-legend">
        <span><i className="seat-cell occupied" />占用</span>
        <span><i className="seat-cell free" />空闲</span>
        <span><i className="seat-cell cleaning" />待清理</span>
      </div>

      <div className="area-bars">
        {areas.map((area) => (
          <div className="area-row" key={area.area}>
            <span>{area.area} 区</span>
            <div className="bar-track">
              <div className="bar-fill seat" style={{ width: `${Math.max(3, area.utilization * 100)}%` }} />
            </div>
            <strong>{formatPercent(area.utilization)}</strong>
          </div>
        ))}
      </div>
    </div>
  )
}

function statusLabel(status) {
  if (status === 'occupied') return '占用'
  if (status === 'cleaning') return '待清理'
  return '空闲'
}

export default SeatHeatmap
