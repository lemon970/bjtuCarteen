import { formatNumber, read, toNumber } from '../utils/simulation'

/**
 * RFC-011A 等待体验代理 panel。
 *
 * 数据来源:summary.wait_experience_proxy_metrics(party-weighted 样本 < 50 时
 * 后端整对象写为 null,本组件相应不渲染)。
 */
export default function WaitExperienceProxyPanel({ summary }) {
  const metrics = read(summary, 'wait_experience_proxy_metrics', 'waitExperienceProxyMetrics')
  if (!metrics || typeof metrics !== 'object') {
    return null
  }

  const proxyIndex = read(metrics, 'wait_experience_proxy_index', 'waitExperienceProxyIndex')
  const preProcess = read(metrics, 'pre_process_wait_share', 'preProcessWaitShare')
  const uncertainty = read(metrics, 'wait_uncertainty_score', 'waitUncertaintyScore')
  const anxiety = read(metrics, 'anxiety_pressure_index', 'anxietyPressureIndex')
  const soloAdjusted = read(metrics, 'solo_adjusted_wait_minutes', 'soloAdjustedWaitMinutes')
  const sampleCount = read(metrics, 'sample_count', 'sampleCount')

  return (
    <section className="panel" data-testid="wait-experience-proxy-panel">
      <div className="panel-title">
        <div>
          <h2>等待体验代理(RFC-011A)</h2>
          <p>
            等权融合的启发式代理指标,仅用于同模型内相对比较,不解释为真实感知等待时间。
          </p>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <MetricItem label="综合代理指数" value={formatRatio(proxyIndex)} />
        <MetricItem label="P/I 比" value={formatRatio(preProcess)} />
        <MetricItem label="不确定性" value={formatRatio(uncertainty)} />
        <MetricItem label="焦虑压力" value={formatRatio(anxiety)} />
        <MetricItem label="独食调整等待" value={formatMinutes(soloAdjusted)} />
        <MetricItem label="样本数" value={formatInt(sampleCount)} />
      </div>
    </section>
  )
}

function formatRatio(value) {
  if (value === null || value === undefined) return '-'
  const n = toNumber(value, NaN)
  return Number.isFinite(n) ? formatNumber(n, 3) : '-'
}

function formatMinutes(value) {
  if (value === null || value === undefined) return '-'
  const n = toNumber(value, NaN)
  return Number.isFinite(n) ? `${formatNumber(n, 2)} 分钟` : '-'
}

function formatInt(value) {
  if (value === null || value === undefined) return '-'
  const n = toNumber(value, NaN)
  return Number.isFinite(n) ? String(Math.round(n)) : '-'
}

function MetricItem({ label, value }) {
  return (
    <div className="rounded-xl bg-canvas-base px-3 py-2">
      <div className="text-xs text-slate-500">{label}</div>
      <div className="mt-1 font-numeric font-semibold tabular-nums text-bjtu-700">{value}</div>
    </div>
  )
}
