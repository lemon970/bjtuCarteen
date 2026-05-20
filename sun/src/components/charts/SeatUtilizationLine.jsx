import { useMemo } from 'react'

import { useEcharts } from '../../utils/useEcharts'
import { normalizePoint } from '../../utils/simulation'

function SeatUtilizationLine({ timeline }) {
  const points = useMemo(
    () => (Array.isArray(timeline) ? timeline.map(normalizePoint) : []),
    [timeline]
  )

  const option = useMemo(() => {
    const peak = points.reduce(
      (best, p, idx) => (p.seatRate > best.value ? { value: p.seatRate, index: idx, minute: p.minute } : best),
      { value: 0, index: 0, minute: 0 }
    )
    const hasUnavailable = points.some((p) => p.seatUnavailableRate > 0 && p.seatUnavailableRate >= p.seatRate)
    return {
      grid: { left: 56, right: 28, top: 32, bottom: 36 },
      legend: hasUnavailable ? { top: 4, right: 12, textStyle: { color: '#475569', fontSize: 11 } } : { show: false },
      tooltip: {
        trigger: 'axis',
        valueFormatter: (v) => `${(Number(v) * 100).toFixed(1)}%`
      },
      xAxis: {
        type: 'category',
        data: points.map((p) => p.minute),
        boundaryGap: false,
        name: '仿真分钟',
        nameLocation: 'middle',
        nameGap: 24,
        axisLabel: { color: '#64748b' },
        axisLine: { lineStyle: { color: '#cbd5e1' } }
      },
      yAxis: {
        type: 'value',
        name: '座位状态',
        nameTextStyle: { color: '#64748b' },
        axisLabel: { color: '#64748b', formatter: (v) => `${Math.round(v * 100)}%` },
        splitLine: { lineStyle: { color: '#e2e8f0' } },
        max: 1,
        min: 0
      },
      series: [
        {
          name: '已占用',
          type: 'line',
          smooth: true,
          showSymbol: false,
          data: points.map((p) => p.seatRate),
          lineStyle: { color: '#1e40af', width: 2 },
          itemStyle: { color: '#1e40af' },
          areaStyle: { color: '#1e40af', opacity: 0.18 },
          markPoint: {
            symbolSize: 60,
            data: peak.value > 0
              ? [{ name: '峰值', coord: [peak.index, peak.value], value: `${(peak.value * 100).toFixed(0)}%` }]
              : [],
            label: { color: '#fff' },
            itemStyle: { color: '#1e40af' }
          },
          markLine: {
            silent: true,
            symbol: 'none',
            lineStyle: { color: '#94a3b8', type: 'dashed' },
            data: [
              { yAxis: 0.7, label: { formatter: '舒适阈值 70%', color: '#475569' } }
            ]
          }
        },
        {
          name: '不可用 (含预定)',
          type: 'line',
          smooth: true,
          showSymbol: false,
          data: points.map((p) => p.seatUnavailableRate),
          lineStyle: { color: '#b45309', width: 1.5, type: 'dashed' },
          itemStyle: { color: '#b45309' }
        }
      ]
    }
  }, [points])

  const containerRef = useEcharts(option, [option])

  if (!points.length) {
    return <div className="empty-state">暂无占用率数据,请先运行仿真。</div>
  }
  return <div ref={containerRef} className="h-72 w-full" />
}

export default SeatUtilizationLine
