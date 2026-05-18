import { useMemo } from 'react'

import { useEcharts } from '../../utils/useEcharts'

function QueueBarChart({ windowServedCounts = [], windowTypes = [] }) {
  const option = useMemo(() => {
    const labels = windowServedCounts.map((_, idx) => `窗口 ${idx + 1}`)
    const data = windowServedCounts.map((value, idx) => {
      const type = (windowTypes[idx] || '').toUpperCase()
      const isTakeaway = type === 'TAKEAWAY'
      return {
        value,
        itemStyle: {
          color: isTakeaway ? '#f59e0b' : '#1e40af',
          borderRadius: [8, 8, 0, 0]
        }
      }
    })
    return {
      grid: { left: 56, right: 28, top: 24, bottom: 36 },
      tooltip: {
        trigger: 'axis',
        formatter: (params) => {
          const item = params[0]
          const type = (windowTypes[item.dataIndex] || 'NORMAL').toUpperCase()
          const label = type === 'TAKEAWAY' ? '打包窗口' : '普通窗口'
          return `${item.name}<br/>${label}<br/>已服务 <strong>${item.value}</strong> 人`
        }
      },
      xAxis: {
        type: 'category',
        data: labels,
        axisLabel: { color: '#64748b' },
        axisLine: { lineStyle: { color: '#cbd5e1' } }
      },
      yAxis: {
        type: 'value',
        name: '已服务人数',
        nameTextStyle: { color: '#64748b' },
        axisLabel: { color: '#64748b' },
        splitLine: { lineStyle: { color: '#e2e8f0' } }
      },
      series: [
        {
          type: 'bar',
          data,
          barMaxWidth: 56
        }
      ]
    }
  }, [windowServedCounts, windowTypes])

  const containerRef = useEcharts(option, [option])

  if (!windowServedCounts.length) {
    return <div className="empty-state">暂无窗口服务数据。</div>
  }
  return <div ref={containerRef} className="h-72 w-full" />
}

export default QueueBarChart
