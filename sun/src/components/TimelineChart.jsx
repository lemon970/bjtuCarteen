import { useMemo } from 'react'

import { normalizePoint } from '../utils/simulation'

// [重构] 用轻量 SVG 图表替代 dataPre 的 ECharts CDN，原因是当前 Vite 工程无需额外远程依赖。
function TimelineChart({ timeline }) {
  const chartData = useMemo(() => {
    if (!Array.isArray(timeline) || timeline.length === 0) {
      return []
    }
    return timeline.map(normalizePoint)
  }, [timeline])

  if (chartData.length === 0) {
    return <div className="empty-state">暂无趋势快照，请先运行仿真或读取最新报告。</div>
  }

  const width = 720
  const height = 220
  const padding = 28
  const maxValue = Math.max(1, ...chartData.flatMap((item) => [item.queue, item.seats]))
  const xStep = chartData.length <= 1 ? 0 : (width - padding * 2) / (chartData.length - 1)

  const points = (key) =>
    chartData
      .map((item, index) => {
        const x = padding + index * xStep
        const y = height - padding - (item[key] / maxValue) * (height - padding * 2)
        return `${x},${y}`
      })
      .join(' ')

  return (
    <div className="chart-wrap">
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="仿真趋势图">
        <line className="axis" x1={padding} y1={height - padding} x2={width - padding} y2={height - padding} />
        <line className="axis" x1={padding} y1={padding} x2={padding} y2={height - padding} />
        <polyline className="chart-line queue" points={points('queue')} />
        <polyline className="chart-line seats" points={points('seats')} />
      </svg>
      <div className="legend">
        <span><i className="queue-dot" />总排队人数</span>
        <span><i className="seat-dot" />占用座位数</span>
      </div>
    </div>
  )
}

export default TimelineChart
