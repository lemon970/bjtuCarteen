import WaitDistributionBar from './charts/WaitDistributionBar'
import InsightNarrative from './InsightNarrative'
import MetricCard from './MetricCard'
import { formatNumber, formatPercent, read } from '../utils/simulation'

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
    </section>
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
