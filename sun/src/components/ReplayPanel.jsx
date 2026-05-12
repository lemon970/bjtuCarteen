import { useEffect, useState } from 'react'

import FlowAnimation from './FlowAnimation'
import MetricCard from './MetricCard'
import SeatHeatmap from './SeatHeatmap'
import { formatClock, formatNumber, normalizePoint, read } from '../utils/simulation'

// [重构] 吸收 dataPre 的播放/暂停/时间轴交互，但改为 React state，避免直接操作 DOM。
function ReplayPanel({ timeline, seatCells }) {
  const [currentIndex, setCurrentIndex] = useState(0)
  const [playing, setPlaying] = useState(false)

  useEffect(() => {
    setCurrentIndex(0)
    setPlaying(false)
  }, [timeline])

  useEffect(() => {
    if (!playing || !Array.isArray(timeline) || timeline.length <= 1) {
      return undefined
    }
    const timer = window.setInterval(() => {
      setCurrentIndex((prev) => {
        if (prev >= timeline.length - 1) {
          setPlaying(false)
          return prev
        }
        return prev + 1
      })
    }, 1000)
    return () => window.clearInterval(timer)
  }, [playing, timeline])

  const point = Array.isArray(timeline) ? timeline[currentIndex] : null
  const normalized = point ? normalizePoint(point, currentIndex) : null
  const queueSizes = read(point, 'window_queue_sizes', 'windowQueueSizes') || []
  const windowTypes = read(point, 'window_types', 'windowTypes') || []
  const maxQueue = Math.max(1, ...queueSizes)
  const seatRate = normalized ? normalized.seatRate * 100 : 0

  if (!timeline?.length) {
    return <div className="empty-state">暂无回放数据，请先运行仿真或读取最新报告。</div>
  }

  return (
    <div className="replay-panel">
      <div className="replay-controls">
        <button type="button" className="secondary" onClick={() => setPlaying((value) => !value)}>
          {playing ? '暂停' : '播放'}
        </button>
        <span>{formatClock(normalized?.timeSeconds ?? 0)}</span>
        <input
          className="timeline-control"
          type="range"
          min="0"
          max={Math.max(0, timeline.length - 1)}
          value={currentIndex}
          onChange={(event) => setCurrentIndex(Number(event.target.value))}
        />
        <span>{currentIndex + 1} / {timeline.length}</span>
      </div>

      <div className="replay-grid">
        <MetricCard title="当前排队" value={normalized?.queue ?? 0} />
        <MetricCard title="占用座位" value={normalized?.seats ?? 0} />
        <MetricCard title="座位利用率" value={`${formatNumber(seatRate, 1)}%`} />
      </div>

      <div className="realtime-modules">
        <div className="sub-panel">
          <h3>人流动态动画</h3>
          <FlowAnimation point={point} />
        </div>
        <div className="sub-panel">
          <h3>座位占用热力图</h3>
          <SeatHeatmap point={point} fallbackCells={seatCells} />
        </div>
      </div>

      <div className="window-bars">
        {queueSizes.length === 0 ? (
          <div className="empty-state">该时间点没有窗口队列明细。</div>
        ) : (
          queueSizes.map((size, index) => {
            const type = windowTypes[index] || 'NORMAL'
            return (
              <div className="window-row" key={`${index}-${type}`}>
                <span>{type === 'TAKEAWAY' ? '打包窗口' : `普通窗口 ${index + 1}`}</span>
                <div className="bar-track">
                  <div
                    className={type === 'TAKEAWAY' ? 'bar-fill takeaway' : 'bar-fill'}
                    style={{ width: `${Math.max(3, (size / maxQueue) * 100)}%` }}
                  />
                </div>
                <strong>{size}</strong>
              </div>
            )
          })
        )}
      </div>
    </div>
  )
}

export default ReplayPanel
