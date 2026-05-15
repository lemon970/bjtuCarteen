import { useMemo } from 'react'

import { useEcharts } from '../../utils/useEcharts'
import { normalizePoint } from '../../utils/simulation'

const COLOR_QUEUE = '#1e40af'
const COLOR_SEATS = '#0d9488'
const COLOR_ARRIVED = '#f59e0b'

function TrendChart({ timeline }) {
  const points = useMemo(
    () => (Array.isArray(timeline) ? timeline.map(normalizePoint) : []),
    [timeline]
  )

  const option = useMemo(() => {
    const minutes = points.map((p) => p.minute)
    return {
      grid: { left: 56, right: 56, top: 50, bottom: 36 },
      tooltip: {
        trigger: 'axis',
        valueFormatter: (v) => Number(v).toFixed(0)
      },
      legend: {
        top: 8,
        textStyle: { color: '#475569' },
        data: ['总排队人数', '占用座位', '累计到达']
      },
      xAxis: {
        type: 'category',
        data: minutes,
        boundaryGap: false,
        name: '仿真分钟',
        nameLocation: 'middle',
        nameGap: 24,
        axisLabel: { color: '#64748b' },
        axisLine: { lineStyle: { color: '#cbd5e1' } }
      },
      yAxis: [
        {
          type: 'value',
          name: '人数 / 座位',
          nameTextStyle: { color: '#64748b', padding: [0, 0, 0, 28] },
          axisLabel: { color: '#64748b' },
          splitLine: { lineStyle: { color: '#e2e8f0' } }
        },
        {
          type: 'value',
          name: '累计到达',
          nameTextStyle: { color: '#64748b', padding: [0, 28, 0, 0] },
          axisLabel: { color: '#64748b' },
          splitLine: { show: false }
        }
      ],
      series: [
        {
          name: '总排队人数',
          type: 'line',
          smooth: true,
          showSymbol: false,
          data: points.map((p) => p.queue),
          itemStyle: { color: COLOR_QUEUE },
          lineStyle: { color: COLOR_QUEUE, width: 2 },
          areaStyle: { color: 'rgba(30, 64, 175, 0.12)' }
        },
        {
          name: '占用座位',
          type: 'line',
          smooth: true,
          showSymbol: false,
          data: points.map((p) => p.seats),
          itemStyle: { color: COLOR_SEATS },
          lineStyle: { color: COLOR_SEATS, width: 2 }
        },
        {
          name: '累计到达',
          type: 'line',
          yAxisIndex: 1,
          smooth: true,
          showSymbol: false,
          data: points.map((p) => p.arrived),
          itemStyle: { color: COLOR_ARRIVED },
          lineStyle: { color: COLOR_ARRIVED, width: 2, type: 'dashed' }
        }
      ]
    }
  }, [points])

  const containerRef = useEcharts(option, [option])

  if (!points.length) {
    return <div className="empty-state">暂无趋势快照,请先运行仿真或读取最新报告。</div>
  }
  return <div ref={containerRef} className="h-72 w-full" />
}

export default TrendChart
