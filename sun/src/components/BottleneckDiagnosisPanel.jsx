import { formatNumber, read, toNumber } from '../utils/simulation'

/**
 * RFC-012 瓶颈诊断 panel。
 *
 * 数据来源:summary.bottleneck_diagnosis(后端 BottleneckAnalyzer 保证非 null,
 * 即使输入全空也回 BALANCED;此处 null 防御仅守后端意外回退)。
 *
 * 三条渲染分支:
 *  - null:整 panel 不渲染
 *  - primary == "balanced":绿色 banner
 *  - 触发:primary 卡片 + optional secondary + evidence 表(后端排序顺序)
 */
export default function BottleneckDiagnosisPanel({ summary }) {
  const diagnosis = read(summary, 'bottleneck_diagnosis', 'bottleneckDiagnosis')
  if (!diagnosis || typeof diagnosis !== 'object') {
    return null
  }

  const primary = String(read(diagnosis, 'primary') || '').toLowerCase()
  const secondary = String(read(diagnosis, 'secondary') || '').toLowerCase() || null
  const bottlenecks = Array.isArray(read(diagnosis, 'bottlenecks')) ? read(diagnosis, 'bottlenecks') : []
  const isBalanced = primary === 'balanced'

  return (
    <section
      className="panel"
      data-testid="bottleneck-diagnosis-panel"
      data-state={isBalanced ? 'balanced' : 'triggered'}
    >
      <div className="panel-title">
        <div>
          <h2>瓶颈诊断(RFC-012)</h2>
          <p>
            基于 SimulationSummary 已有字段的派生启发式诊断。utilization 触发阈值 0.85,3 段严重度。仅用于同模型内相对比较。
          </p>
        </div>
      </div>

      {isBalanced ? (
        <div className="rounded-xl border-l-4 border-emerald-500 bg-emerald-50 px-4 py-3">
          <div className="text-sm font-semibold text-emerald-700">✓ 无明显瓶颈</div>
          <div className="mt-1 text-xs text-emerald-700/80">
            所有 4 类资源(非打包窗口、座位、打包窗口、到达冲击)利用率均 &lt; 0.85,系统处于均衡状态。
          </div>
        </div>
      ) : (
        <>
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            <BottleneckCard
              testId="bottleneck-card-primary"
              tag="PRIMARY"
              type={primary}
              severity={severityOf(bottlenecks[0])}
            />
            {secondary && bottlenecks.length >= 2 ? (
              <BottleneckCard
                testId="bottleneck-card-secondary"
                tag="SECONDARY"
                type={secondary}
                severity={severityOf(bottlenecks[1])}
              />
            ) : null}
          </div>
          <div className="mt-4 overflow-auto rounded-xl border border-canvas-border" data-testid="bottleneck-evidence-table">
            <table className="table-base">
              <thead>
                <tr>
                  <th>类型</th>
                  <th>严重度</th>
                  <th>指标</th>
                  <th>实测</th>
                  <th>阈值</th>
                  <th>窗口</th>
                </tr>
              </thead>
              <tbody>
                {bottlenecks.map((b, idx) => {
                  const type = String(read(b, 'type') || '').toLowerCase()
                  const sev = String(read(b, 'severity') || '').toLowerCase()
                  const ev = read(b, 'evidence') || {}
                  const metricName = read(ev, 'metric_name', 'metricName') || '-'
                  const observed = read(ev, 'observed_value', 'observedValue')
                  const threshold = read(ev, 'threshold')
                  const windowId = read(ev, 'window_id', 'windowId')
                  return (
                    <tr key={`${type}-${idx}`} data-testid={`bottleneck-evidence-row-${idx}`} data-severity={sev}>
                      <td>{TYPE_LABEL[type] || type}</td>
                      <td>
                        <span className={`pill ${SEVERITY_PILL[sev] || 'bg-slate-100 text-slate-700'}`}>
                          {sev.toUpperCase()}
                        </span>
                      </td>
                      <td className="font-numeric tabular-nums">{metricName}</td>
                      <td className="font-numeric tabular-nums">{formatRatio(observed)}</td>
                      <td className="font-numeric tabular-nums">{formatRatio(threshold)}</td>
                      <td className="font-numeric tabular-nums">{formatWindowId(windowId)}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </>
      )}
    </section>
  )
}

const TYPE_LABEL = {
  window_service_capacity: '窗口服务能力',
  seat_capacity: '座位容量',
  takeaway_capacity: '打包窗口',
  arrival_surge: '到达冲击',
  balanced: '无瓶颈'
}

const SEVERITY_PILL = {
  high: 'bg-rose-100 text-rose-700 ring-rose-200',
  medium: 'bg-amber-100 text-amber-700 ring-amber-200',
  low: 'bg-yellow-100 text-yellow-700 ring-yellow-200'
}

const SEVERITY_BORDER = {
  high: 'border-l-4 border-rose-500 bg-rose-50',
  medium: 'border-l-4 border-amber-500 bg-amber-50',
  low: 'border-l-4 border-yellow-500 bg-yellow-50'
}

function severityOf(bottleneck) {
  return String(read(bottleneck || {}, 'severity') || '').toLowerCase()
}

function BottleneckCard({ testId, tag, type, severity }) {
  const sevClass = SEVERITY_BORDER[severity] || 'border-l-4 border-slate-300 bg-slate-50'
  return (
    <div data-testid={testId} data-severity={severity} className={`rounded-xl px-4 py-3 ${sevClass}`}>
      <div className="text-xs font-semibold text-slate-500">
        {tag} · {severity ? severity.toUpperCase() : '-'}
      </div>
      <div className="mt-1 text-base font-semibold text-slate-900">
        {TYPE_LABEL[type] || type}
      </div>
    </div>
  )
}

function formatRatio(value) {
  if (value === null || value === undefined) return '-'
  const n = toNumber(value, NaN)
  return Number.isFinite(n) ? formatNumber(n, 3) : '-'
}

function formatWindowId(value) {
  if (value === null || value === undefined) return '—'
  const n = toNumber(value, NaN)
  if (!Number.isFinite(n)) return '—'
  if (n < 0) return '—'
  return `窗口 ID #${Math.round(n)}`
}
