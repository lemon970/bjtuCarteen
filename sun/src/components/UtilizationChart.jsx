import { useMemo } from 'react'

import { formatPercent, normalizePoint } from '../utils/simulation'

function UtilizationChart({ timeline }) {
  const data = useMemo(() => {
    if (!Array.isArray(timeline)) {
      return []
    }
    return timeline.map(normalizePoint)
  }, [timeline])

  if (!data.length) {
    return <div className="empty-state">暂无占用率数据，请先运行仿真或读取最新报告。</div>
  }

  const width = 720
  const height = 220
  const padding = 32
  const xStep = data.length <= 1 ? 0 : (width - padding * 2) / (data.length - 1)
  const peak = data.reduce((best, item, index) => (
    item.seatRate > best.seatRate ? { ...item, index } : best
  ), { seatRate: 0, minute: 0, index: 0 })

  const linePoints = data.map((item, index) => {
    const x = padding + index * xStep
    const y = height - padding - item.seatRate * (height - padding * 2)
    return `${x},${y}`
  }).join(' ')

  const areaPoints = `${padding},${height - padding} ${linePoints} ${width - padding},${height - padding}`
  const peakX = padding + peak.index * xStep
  const peakY = height - padding - peak.seatRate * (height - padding * 2)

  return (
    <div className="chart-wrap utilization-chart">
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="座位占用率趋势图">
        {[0, 0.25, 0.5, 0.75, 1].map((tick) => {
          const y = height - padding - tick * (height - padding * 2)
          return (
            <g key={tick}>
              <line className="grid-line" x1={padding} y1={y} x2={width - padding} y2={y} />
              <text className="axis-label" x={4} y={y + 4}>{Math.round(tick * 100)}%</text>
            </g>
          )
        })}
        <polygon className="utilization-area" points={areaPoints} />
        <polyline className="chart-line utilization" points={linePoints} />
        <circle className="peak-point" cx={peakX} cy={peakY} r="5" />
        <text className="peak-label" x={Math.min(width - 150, peakX + 10)} y={Math.max(20, peakY - 10)}>
          峰值 {formatPercent(peak.seatRate)} / 第 {peak.minute} 分钟
        </text>
      </svg>
      <div className="legend">
        <span><i className="utilization-dot" />座位占用率</span>
        <span>峰值 {formatPercent(peak.seatRate)}</span>
      </div>
    </div>
  )
}

export default UtilizationChart
