import { useMemo } from 'react'

import WaitDistributionBar from './charts/WaitDistributionBar'
import InsightNarrative from './InsightNarrative'
import MetricCard from './MetricCard'
import { formatNumber, formatPercent, read, toNumber } from '../utils/simulation'
import { useEcharts } from '../utils/useEcharts'

function WaitTimePanel({ summary }) {
  const insight = read(summary, 'wait_time_insight', 'waitTimeInsight') || {}
  const status = read(insight, 'status') || statusFromTypical(summary)
  const typical = waitValue(summary, 'typical_wait_time_minutes', 'typicalWaitTimeMinutes')
  const raw = waitValue(summary, 'raw_avg_wait_time_minutes', 'rawAvgWaitTimeMinutes', 'avg_wait_time_minutes', 'avgWaitTimeMinutes')
  const steady = waitValue(summary, 'steady_avg_wait_time_minutes', 'steadyAvgWaitTimeMinutes')
  const median = waitValue(summary, 'median_wait_time_minutes', 'medianWaitTimeMinutes')
  const p75 = waitValue(summary, 'p75_wait_time_minutes', 'p75WaitTimeMinutes')
  const p90 = waitValue(summary, 'p90_wait_time_minutes', 'p90WaitTimeMinutes')
  const longWaitRate = read(summary, 'long_wait_rate', 'longWaitRate') ?? 0
  const edgeRate = read(summary, 'edge_wait_sample_rate', 'edgeWaitSampleRate') ?? 0
  const distribution = read(summary, 'wait_time_distribution', 'waitTimeDistribution') || []

  return (
    <section className="panel">
      <div className="panel-title">
        <div>
          <h2>等待体验模型</h2>
          <p>主指标使用稳态样本的 10% 截尾均值,降低开头零等待和结尾排空样本的干扰。</p>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6 mb-6">
        <MetricCard title="典型等待" value={`${formatNumber(typical)} 分钟`} benchmark="0-5 正常,5-10 轻度拥堵" status={status} />
        <MetricCard title="稳态均值" value={`${formatNumber(steady)} 分钟`} benchmark="中间 80% 仿真时间" />
        <MetricCard title="全量均值" value={`${formatNumber(raw)} 分钟`} benchmark="兼容旧接口" />
        <MetricCard
          title="P50 / P75 / P90"
          value={`${formatNumber(median)} / ${formatNumber(p75)} / ${formatNumber(p90)}`}
          benchmark="分钟"
          status={p90 > 15 ? 'critical' : p90 > 10 ? 'warning' : 'info'}
        />
        <MetricCard title="长等待率" value={formatPercent(longWaitRate)} benchmark="建议低于 20%" status={longWaitRate > 0.2 ? 'warning' : 'ok'} />
        <MetricCard title="边界样本占比" value={formatPercent(edgeRate)} benchmark="建议低于 25%" status={edgeRate > 0.25 ? 'warning' : 'ok'} />
      </div>

      <InsightNarrative
        title={read(insight, 'primary_reason', 'primaryReason') || '等待体验解读'}
        items={[
          read(insight, 'message'),
          p90 - median > 8 ? 'P90 明显高于 P50,少数学生承担了较长等待。' : '',
          edgeRate > 0.25 ? '边界样本占比较高,全量均值容易被开头/结尾阶段拉偏。' : ''
        ]}
      />

      <div className="mt-6">
        <h3 className="text-base font-semibold text-slate-900 mb-2">等待时间分布</h3>
        <WaitDistributionBar distribution={distribution} />
      </div>

      <WaitWindowTrend timeline={read(summary, 'timeline') || []} />
    </section>
  )
}

function WaitWindowTrend({ timeline }) {
  const points = useMemo(() => {
    return (Array.isArray(timeline) ? timeline : []).map((point) => {
      const minute = toNumber(read(point, 'minute'), 0)
      const avg = toNumber(read(point, 'avg_wait_minutes_window', 'avgWaitMinutesWindow'), 0)
      const count = toNumber(read(point, 'wait_sample_count_window', 'waitSampleCountWindow'), 0)
      return { minute, avg, count }
    })
  }, [timeline])

  const option = useMemo(() => ({
    grid: { left: 56, right: 24, top: 16, bottom: 32 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      formatter: (params) => {
        const item = params[0]
        const sample = points[item.dataIndex] || {}
        return `第 ${item.value[0]} 分钟<br/>5 分钟均值 <strong>${(item.value[1] || 0).toFixed(2)}</strong> 分钟<br/>窗口内样本 ${sample.count || 0} 人`
      }
    },
    xAxis: {
      type: 'value',
      name: '分钟',
      axisLabel: { color: '#64748b' },
      splitLine: { lineStyle: { color: '#e2e8f0' } }
    },
    yAxis: {
      type: 'value',
      name: '等待 (分钟)',
      axisLabel: { color: '#64748b' },
      splitLine: { lineStyle: { color: '#e2e8f0' } }
    },
    series: [
      {
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: points.map((p) => [p.minute, p.avg]),
        lineStyle: { color: '#0f766e', width: 2 },
        areaStyle: { color: 'rgba(15, 118, 110, 0.12)' }
      }
    ]
  }), [points])

  const containerRef = useEcharts(option, [option])

  if (points.length === 0) {
    return null
  }
  return (
    <div className="mt-6">
      <h3 className="text-base font-semibold text-slate-900 mb-2">滑动窗口等待均值时序</h3>
      <p className="mb-2 text-xs text-slate-500">每个时刻取过去 5 分钟内完成服务的学生,按团组人数加权平均。</p>
      <div ref={containerRef} className="h-60 w-full" />
    </div>
  )
}

function waitValue(summary, ...keys) {
  const value = read(summary, ...keys)
  if (value !== undefined && value !== null) {
    return Number(value) || 0
  }
  return Number(read(summary, 'avg_wait_time_minutes', 'avgWaitTimeMinutes')) || 0
}

function statusFromTypical(summary) {
  const typical = waitValue(summary, 'typical_wait_time_minutes', 'typicalWaitTimeMinutes')
  if (typical > 15) return 'critical'
  if (typical > 5) return 'warning'
  return 'ok'
}

export default WaitTimePanel
