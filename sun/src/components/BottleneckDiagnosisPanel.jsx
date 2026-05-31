import { formatNumber, read, toNumber } from '../utils/simulation'

/**
 * RFC-012 瓶颈诊断 panel(通俗结论卡 + 折叠详情形态)。
 *
 * 数据来源:summary.bottleneck_diagnosis(后端 BottleneckAnalyzer 保证非 null,
 * 即使输入全空也回 BALANCED;此处 null 防御仅守后端意外回退)。
 *
 * 三条渲染分支:
 *  - null:整 panel 不渲染
 *  - primary == "balanced":单条均衡说明
 *  - 触发:primary 卡(白话结论 + 一句建议),optional secondary 卡;
 *          原 evidence 表收进 <details> 折叠,默认折叠
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
          <h2>瓶颈诊断</h2>
          <p>识别仿真过程中座位、窗口或队列哪一环最先吃紧。结论用白话描述,具体指标可在下方展开。</p>
        </div>
      </div>

      {isBalanced ? (
        <BalancedBanner />
      ) : (
        <>
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            <BottleneckCard
              testId="bottleneck-card-primary"
              tag="主要瓶颈"
              type={primary}
              severity={severityOf(bottlenecks[0])}
            />
            {secondary && bottlenecks.length >= 2 ? (
              <BottleneckCard
                testId="bottleneck-card-secondary"
                tag="次要瓶颈"
                type={secondary}
                severity={severityOf(bottlenecks[1])}
              />
            ) : null}
          </div>
          <details className="mt-4 rounded-xl border border-canvas-border" data-testid="bottleneck-evidence-details">
            <summary className="cursor-pointer px-4 py-2 text-sm text-slate-600">
              查看诊断证据 · {bottlenecks.length} 项
            </summary>
            <div className="overflow-auto px-4 pb-4">
              <table className="table-base" data-testid="bottleneck-evidence-table">
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
                        <td>{TYPE_CONTENT[type]?.label || type}</td>
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
          </details>
        </>
      )}
    </section>
  )
}

const TYPE_CONTENT = {
  seat_capacity: {
    title: '座位偏紧',
    description: '高峰时段座位接近满座,学生持餐找位可能较多。',
    suggestion: '可考虑增加座位数,或缩短平均就餐时长。',
    label: '座位容量'
  },
  window_service_capacity: {
    title: '窗口服务繁忙',
    description: '部分窗口在高峰时段接近满负荷,等待队列被拉长。',
    suggestion: '可考虑增开窗口,或将部分餐品分流到其他窗口。',
    label: '窗口服务能力'
  },
  takeaway_capacity: {
    title: '打包窗口繁忙',
    description: '打包窗口在高峰时段接近满负荷。',
    suggestion: '可考虑增加打包窗口,或调整打包/堂食比例。',
    label: '打包窗口'
  },
  arrival_surge: {
    title: '到达冲击明显',
    description: '队列容量在高峰时段接近上限,可能出现学生离队。',
    suggestion: '可考虑提高队列容量,或错峰开放就餐时间。',
    label: '到达冲击'
  },
  balanced: {
    label: '无瓶颈'
  }
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

function BalancedBanner() {
  return (
    <div className="rounded-xl border-l-4 border-emerald-500 bg-emerald-50 px-4 py-3">
      <div className="text-base font-semibold text-emerald-700">✓ 系统均衡</div>
      <div className="mt-1 text-sm text-emerald-700/80">
        4 类资源(座位、非打包窗口、打包窗口、队列)利用率均低于 0.85 阈值,本次仿真未发现明显瓶颈。
      </div>
    </div>
  )
}

function BottleneckCard({ testId, tag, type, severity }) {
  const sevClass = SEVERITY_BORDER[severity] || 'border-l-4 border-slate-300 bg-slate-50'
  const content = TYPE_CONTENT[type] || { title: type, description: '', suggestion: null }
  const sevLabel = severity ? severity.toUpperCase() : ''
  return (
    <div data-testid={testId} data-severity={severity} className={`rounded-xl px-4 py-3 ${sevClass}`}>
      <div className="text-xs font-semibold uppercase tracking-wide text-slate-500">
        {tag}{sevLabel ? ` · ${sevLabel}` : ''}
      </div>
      <div className="mt-1 text-base font-semibold text-slate-900">{content.title}</div>
      {content.description ? (
        <div className="mt-1 text-sm text-slate-700">{content.description}</div>
      ) : null}
      {content.suggestion ? (
        <div className="mt-2 text-sm text-slate-600">
          <span className="font-semibold text-slate-700">建议:</span> {content.suggestion}
        </div>
      ) : null}
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
