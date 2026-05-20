import { useMemo } from 'react'

import { useEcharts } from '../../utils/useEcharts'
import { read } from '../../utils/simulation'

function WaitDistributionBar({ distribution = [] }) {
  const option = useMemo(() => {
    const data = Array.isArray(distribution) ? distribution : []
    const labels = data.map((bucket) => read(bucket, 'label') || '-')
    const counts = data.map((bucket) => read(bucket, 'count') || 0)
    return {
      grid: { left: 84, right: 32, top: 16, bottom: 32 },
      tooltip: {
        trigger: 'axis',
        formatter: (params) => {
          const item = params[0]
          const bucket = data[item.dataIndex] || {}
          const rate = read(bucket, 'rate') || 0
          return `${item.name} 分钟<br/>人数 <strong>${item.value}</strong> · 占比 ${(rate * 100).toFixed(1)}%`
        }
      },
      xAxis: {
        type: 'value',
        name: '人数',
        nameTextStyle: { color: '#64748b' },
        axisLabel: { color: '#64748b' },
        splitLine: { lineStyle: { color: '#e2e8f0' } }
      },
      yAxis: {
        type: 'category',
        data: labels,
        inverse: true,
        axisLabel: { color: '#475569' },
        axisLine: { lineStyle: { color: '#cbd5e1' } }
      },
      series: [
        {
          type: 'bar',
          data: counts,
          barMaxHeight: 24,
          itemStyle: { color: '#1e40af', borderRadius: [0, 8, 8, 0] }
        }
      ]
    }
  }, [distribution])

  const containerRef = useEcharts(option, [option])

  if (!distribution || !distribution.length) {
    return <div className="empty-state">暂无等待时间分布数据。</div>
  }
  return <div ref={containerRef} className="h-64 w-full" />
}

export default WaitDistributionBar
