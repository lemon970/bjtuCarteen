import { formatNumber, read, toNumber } from '../utils/simulation'

/**
 * RFC-011B 公平性 panel。
 *
 * 数据来源:summary.fairness_metrics(party-weighted 样本 < 50 时
 * 后端整对象写为 null,本组件相应不渲染)。
 *
 * 三项指标的解释来自 service/FairnessCalculator:
 *  - wait_gini:party-weighted 等待时间分布的 Gini(Lorenz 偏离对角线程度)
 *  - non_takeaway_window_load_cv:对 windowTypes != "TAKEAWAY" 子集的 servedCounts 的总体 CV
 *  - cross_role_fairness:solo dine-in / group dine-in / takeaway 三类 party-weighted 中位数等待 max-min(分钟)
 */
export default function FairnessPanel({ summary }) {
  const metrics = read(summary, 'fairness_metrics', 'fairnessMetrics')
  if (!metrics || typeof metrics !== 'object') {
    return null
  }

  const gini = read(metrics, 'wait_gini', 'waitGini')
  const cv = read(metrics, 'non_takeaway_window_load_cv', 'nonTakeawayWindowLoadCv')
  const crossRole = read(metrics, 'cross_role_fairness', 'crossRoleFairness')
  const sampleCount = read(metrics, 'sample_count', 'sampleCount')

  return (
    <section className="panel" data-testid="fairness-panel">
      <div className="panel-title">
        <div>
          <h2>公平性(RFC-011B)</h2>
          <p>
            party-weighted 样本展开;CV 与 Gini 分别按非打包窗口负载与等待时间分布计算,跨角色差异比较 solo / group / 打包三类中位数等待。
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
        <FairnessRow
          testId="fairness-row-gini"
          label="等待 GINI"
          value={formatRatio(gini)}
          hint="0 = 完全公平,1 = 极端不公平"
          status={ratioStatus(gini, [0.20, 0.40])}
        />
        <FairnessRow
          testId="fairness-row-cv"
          label="非打包窗口负载 CV"
          value={formatRatio(cv)}
          hint="stddev / mean,对非打包窗口集合"
          status={ratioStatus(cv, [0.20, 0.30])}
        />
        <FairnessRow
          testId="fairness-row-cross-role"
          label="跨角色差异"
          value={formatMinutes(crossRole)}
          hint="solo / group / 打包 中位数 max - min"
          status={minutesStatus(crossRole, [3, 6])}
        />
        <FairnessRow
          testId="fairness-row-sample"
          label="样本数"
          value={formatInt(sampleCount)}
          hint="party-weighted"
        />
      </div>
    </section>
  )
}

function ratioStatus(value, [warnAt, badAt]) {
  const n = toNumber(value, NaN)
  if (!Number.isFinite(n)) return null
  if (n >= badAt) return 'fairness-status-bad'
  if (n >= warnAt) return 'fairness-status-warn'
  return 'fairness-status-ok'
}

function minutesStatus(value, [warnAt, badAt]) {
  const n = toNumber(value, NaN)
  if (!Number.isFinite(n)) return null
  if (n >= badAt) return 'fairness-status-bad'
  if (n >= warnAt) return 'fairness-status-warn'
  return 'fairness-status-ok'
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

const STATUS_CLASS = {
  'fairness-status-ok': 'border-l-4 border-emerald-500',
  'fairness-status-warn': 'border-l-4 border-amber-500',
  'fairness-status-bad': 'border-l-4 border-rose-500'
}

function FairnessRow({ testId, label, value, hint, status }) {
  const statusClass = status ? STATUS_CLASS[status] : ''
  return (
    <div
      data-testid={testId}
      data-status={status || ''}
      className={`rounded-xl bg-canvas-base px-3 py-2 ${statusClass}`}
    >
      <div className="text-xs text-slate-500">{label}</div>
      <div className="mt-1 font-numeric font-semibold tabular-nums text-bjtu-700">{value}</div>
      {hint ? <div className="mt-1 text-xs text-slate-400">{hint}</div> : null}
    </div>
  )
}
