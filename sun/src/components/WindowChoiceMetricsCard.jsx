import { formatNumber, formatPercent, read, toNumber } from '../utils/simulation'

/**
 * RFC-009 PR-9D 前端入口:展示 PREFERENCE_AWARE 模式下后端写出的
 * summary.window_choice_metrics。STATIC_SPLIT 报告中该字段不存在,
 * 组件直接返回 null,不渲染。
 *
 * 仅做只读展示,不引入新图表库 / 第三方依赖。
 */
export default function WindowChoiceMetricsCard({ summary }) {
  const metrics = read(summary, 'window_choice_metrics', 'windowChoiceMetrics')
  if (!metrics || typeof metrics !== 'object') {
    return null
  }

  const queueChoiceModel = read(metrics, 'queue_choice_model', 'queueChoiceModel') || '-'
  const popularCount = read(metrics, 'popular_window_count', 'popularWindowCount')
  const normalCount = read(metrics, 'normal_window_count', 'normalWindowCount')
  const coldCount = read(metrics, 'cold_window_count', 'coldWindowCount')
  const takeawayCount = read(metrics, 'takeaway_window_count', 'takeawayWindowCount')

  const popPref = read(metrics, 'popular_preference_share', 'popularPreferenceShare')
  const normPref = read(metrics, 'normal_preference_share', 'normalPreferenceShare')
  const coldPref = read(metrics, 'cold_preference_share', 'coldPreferenceShare')

  const popServed = read(metrics, 'popular_served_share', 'popularServedShare')
  const normServed = read(metrics, 'normal_served_share', 'normalServedShare')
  const coldServed = read(metrics, 'cold_served_share', 'coldServedShare')

  const popWait = read(metrics, 'popular_avg_wait_minutes', 'popularAvgWaitMinutes')
  const normWait = read(metrics, 'normal_avg_wait_minutes', 'normalAvgWaitMinutes')
  const coldWait = read(metrics, 'cold_avg_wait_minutes', 'coldAvgWaitMinutes')

  const maxGap = read(metrics, 'max_window_queue_gap', 'maxWindowQueueGap')
  const cv = read(metrics, 'window_served_count_cv', 'windowServedCountCv')

  return (
    <section className="panel" data-testid="window-choice-metrics-card">
      <div className="panel-title">
        <div>
          <h2>窗口选择模型分布(RFC-009 实验)</h2>
          <p>
            该面板由 PREFERENCE_AWARE 模式生成,展示偏好与服务在热门 / 普通 / 冷门窗口的分布。
            实验模型,仅用于仿真对比,不代表真实标定。
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-6 md:grid-cols-2 xl:grid-cols-3">
        <ParamList
          title="窗口角色拆分"
          items={[
            ['队列模型', queueChoiceModel],
            ['热门窗口数', formatInt(popularCount)],
            ['普通窗口数', formatInt(normalCount)],
            ['冷门窗口数', formatInt(coldCount)],
            ['打包窗口数', formatInt(takeawayCount)]
          ]}
        />
        <ParamList
          title="偏好份额(普通窗口集合内归一)"
          items={[
            ['热门 popular', formatPctOrDash(popPref)],
            ['普通 normal', formatPctOrDash(normPref)],
            ['冷门 cold', formatPctOrDash(coldPref)]
          ]}
        />
        <ParamList
          title="服务份额(普通窗口集合内归一)"
          items={[
            ['热门 popular', formatPctOrDash(popServed)],
            ['普通 normal', formatPctOrDash(normServed)],
            ['冷门 cold', formatPctOrDash(coldServed)]
          ]}
        />
        <ParamList
          title="平均等待 / 分钟"
          items={[
            ['热门 popular', formatMinutesOrDash(popWait)],
            ['普通 normal', formatMinutesOrDash(normWait)],
            ['冷门 cold', formatMinutesOrDash(coldWait)]
          ]}
        />
        <ParamList
          title="队列失衡指标"
          items={[
            ['普通窗口最大队差', formatInt(maxGap)],
            ['完成数变异系数 CV', formatNumberOrDash(cv, 3)]
          ]}
        />
      </div>
    </section>
  )
}

function formatInt(value) {
  const n = Number(value)
  return Number.isFinite(n) ? String(Math.round(n)) : '-'
}

function formatPctOrDash(value) {
  if (value === null || value === undefined) return '-'
  const n = toNumber(value, NaN)
  return Number.isFinite(n) ? formatPercent(n, 1) : '-'
}

function formatMinutesOrDash(value) {
  if (value === null || value === undefined) return '-'
  const n = toNumber(value, NaN)
  return Number.isFinite(n) ? `${formatNumber(n, 2)} 分` : '-'
}

function formatNumberOrDash(value, digits = 3) {
  if (value === null || value === undefined) return '-'
  const n = toNumber(value, NaN)
  return Number.isFinite(n) ? formatNumber(n, digits) : '-'
}

function ParamList({ title, items }) {
  return (
    <div>
      <h3 className="text-sm font-semibold text-slate-700">{title}</h3>
      <dl className="mt-2 space-y-2 text-sm">
        {items.map(([label, value]) => (
          <div key={label} className="flex items-center justify-between rounded-xl bg-canvas-base px-3 py-2">
            <dt className="text-slate-500">{label}</dt>
            <dd className="font-numeric font-semibold tabular-nums text-bjtu-700">{value}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}
