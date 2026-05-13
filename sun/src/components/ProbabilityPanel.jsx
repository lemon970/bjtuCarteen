import { formatNumber, formatPercent, read } from '../utils/simulation'

const MODEL_ITEMS = [
  {
    key: 'arrival',
    title: '单位时间到达人数',
    value: '泊松分布',
    basis: 'λ 使用输入页到达率，峰值曲线只改变每分钟权重。',
    range: '总人数应等于 到达率 × 时长。'
  },
  {
    key: 'interval',
    title: '到达间隔',
    value: '负指数分布',
    basis: '分钟内到达偏移按指数间隔生成。',
    range: '均值约为 3600 / λ 秒。'
  },
  {
    key: 'takeaway',
    title: '打包决策',
    value: '有界增量模型',
    basis: '基础概率 + 偏好 + 座位压力 + 等待压力 + 队列压力 + 天气影响。',
    range: '晴天低压约 12%~20%，高压不超过模型上限。'
  }
]

function ProbabilityPanel({ summary }) {
  const model = read(summary, 'probability_model', 'probabilityModel') || {}
  const samples = read(summary, 'arrival_samples', 'arrivalSamples') || []
  const decisions = read(summary, 'takeaway_decision_records', 'takeawayDecisionRecords') || []
  const samplePreview = Array.isArray(samples) ? samples.slice(0, 8) : []
  const decisionPreview = Array.isArray(decisions) ? decisions.slice(-8).reverse() : []
  const minuteBuckets = buildMinuteBuckets(samples)
  const intervalBuckets = buildIntervalBuckets(samples)
  const reasonBuckets = buildReasonBuckets(decisions)
  const takeawayRate = read(summary, 'takeaway_rate', 'takeawayRate') ?? 0

  return (
    <div className="probability-panel">
      <div className="probability-grid">
        <MetricItem label="人数分布" value={read(model, 'arrival_count_distribution', 'arrivalCountDistribution') || 'POISSON'} />
        <MetricItem label="到达间隔" value={read(model, 'interarrival_distribution', 'interarrivalDistribution') || 'NEGATIVE_EXPONENTIAL'} />
        <MetricItem label="λ / 小时" value={formatNumber(read(model, 'configured_arrival_lambda_per_hour', 'configuredArrivalLambdaPerHour') ?? 0, 1)} />
        <MetricItem label="间隔拟合度" value={formatPercent(read(model, 'observed_interarrival_accuracy', 'observedInterarrivalAccuracy') ?? 0)} />
        <MetricItem label="期望间隔" value={`${formatNumber(read(model, 'expected_mean_interarrival_seconds', 'expectedMeanInterarrivalSeconds') ?? 0, 1)} 秒`} />
        <MetricItem label="观测间隔" value={`${formatNumber(read(model, 'observed_mean_interarrival_seconds', 'observedMeanInterarrivalSeconds') ?? 0, 1)} 秒`} />
        <MetricItem label="打包率" value={formatPercent(takeawayRate)} status={takeawayRate > 0.25 ? 'warn' : 'ok'} />
        <MetricItem label="方差均值比" value={formatNumber(read(model, 'observed_minute_count_variance_mean_ratio', 'observedMinuteCountVarianceMeanRatio') ?? 0, 2)} />
      </div>

      <div className="model-explain-grid">
        {MODEL_ITEMS.map((item) => (
          <article className="model-explain-card" key={item.key}>
            <span>{item.title}</span>
            <strong>{item.value}</strong>
            <p>计算依据：{item.basis}</p>
            <p>参考范围：{item.range}</p>
          </article>
        ))}
      </div>

      <div className="distribution-grid">
        <BarList title="分钟到达人数" items={minuteBuckets} />
        <BarList title="到达间隔分布" items={intervalBuckets} />
        <BarList title="打包原因分布" items={reasonBuckets} />
      </div>

      <div className="mini-table-grid">
        <div className="mini-table">
          <h3>到达间隔样本</h3>
          {samplePreview.map((item, index) => (
            <p key={`${read(item, 'time_seconds', 'timeSeconds')}-${index}`}>
              第 {read(item, 'minute') ?? 0} 分钟，间隔 {read(item, 'interval_seconds', 'intervalSeconds') ?? 0} 秒，组内 {read(item, 'party_size', 'partySize') || 1} 人
            </p>
          ))}
        </div>
        <div className="mini-table">
          <h3>概率项参考</h3>
          <p>基础概率：输入页的晴天或场景锚点，低压下最终概率围绕它小幅波动。</p>
          <p>座位压力：占用率超过 85% 后增长，超过 90% 进入高压参考区间。</p>
          <p>等待压力：排队等待超过 6 分钟后增长，超过 12 分钟进入高压参考区间。</p>
          <p>队列压力：总队列接近窗口容量时增长，用于解释拥堵转打包。</p>
        </div>
      </div>

      <div className="decision-path-panel">
        <div className="panel-head">
          <div>
            <h3>打包决策路径</h3>
            <p>每条样本展示从基础概率到最终概率的完整推导，避免多个概率值不可解读。</p>
          </div>
        </div>
        {decisionPreview.length === 0 ? (
          <div className="empty-state">暂无打包决策记录</div>
        ) : (
          <div className="decision-list">
            {decisionPreview.map((item) => (
              <DecisionRow item={item} key={`${read(item, 'student_id', 'studentId')}-${read(item, 'time_seconds', 'timeSeconds')}`} />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

function MetricItem({ label, value, status }) {
  return (
    <div className={status ? `probability-metric ${status}` : 'probability-metric'}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}

function DecisionRow({ item }) {
  const base = read(item, 'base_probability', 'baseProbability') ?? 0
  const preference = read(item, 'preference_factor', 'preferenceFactor') ?? 0
  const seat = read(item, 'seat_pressure_factor', 'seatPressureFactor') ?? 0
  const wait = read(item, 'wait_pressure_factor', 'waitPressureFactor') ?? 0
  const queue = read(item, 'queue_pressure_factor', 'queuePressureFactor') ?? 0
  const weather = read(item, 'weather_factor', 'weatherFactor') ?? 0
  const finalProbability = read(item, 'final_probability', 'finalProbability') ?? 0
  const takeaway = Boolean(read(item, 'takeaway'))
  const decisionReason = read(item, 'decision_reason', 'decisionReason') || reasonLabel(read(item, 'reason') || '')
  const windowReason = read(item, 'window_choice_reason', 'windowChoiceReason') || '普通窗口完成服务'

  return (
    <article className="decision-row">
      <div className="decision-main">
        <span className={takeaway ? 'status-pill warn' : 'status-pill ok'}>{takeaway ? '打包' : '堂食'}</span>
        <strong>{formatPercent(finalProbability)}</strong>
        <small>随机数 {formatPercent(read(item, 'random_roll', 'randomRoll') ?? 0)}，等待 {formatNumber(read(item, 'wait_minutes', 'waitMinutes') ?? 0, 2)} 分钟</small>
      </div>
      <div className="decision-formula">
        <span>基础 {formatPercent(base)}</span>
        <span>{formatDelta('偏好', preference)}</span>
        <span>{formatDelta('座位', seat)}</span>
        <span>{formatDelta('等待', wait)}</span>
        <span>{formatDelta('队列', queue)}</span>
        <span>{formatDelta('天气', weather)}</span>
      </div>
      <p>{windowReason}；{decisionReason}</p>
    </article>
  )
}

function formatDelta(label, value) {
  const sign = value >= 0 ? '+' : ''
  return `${label} ${sign}${formatPercent(value)}`
}

function BarList({ title, items }) {
  const max = Math.max(1, ...items.map((item) => item.value))

  return (
    <div className="mini-table chart-list">
      <h3>{title}</h3>
      {items.length === 0 ? (
        <p>暂无样本数据</p>
      ) : (
        items.map((item) => (
          <div className="distribution-row" key={item.label}>
            <span>{item.label}</span>
            <div className="bar-track">
              <div className="bar-fill probability" style={{ width: `${Math.max(4, (item.value / max) * 100)}%` }} />
            </div>
            <strong>{item.value}</strong>
          </div>
        ))
      )}
    </div>
  )
}

function buildMinuteBuckets(samples) {
  if (!Array.isArray(samples) || samples.length === 0) {
    return []
  }
  const map = new Map()
  for (const sample of samples) {
    const minute = read(sample, 'minute') ?? 0
    map.set(minute, (map.get(minute) || 0) + (read(sample, 'party_size', 'partySize') || 1))
  }
  return [...map.entries()]
    .sort((a, b) => a[0] - b[0])
    .slice(0, 12)
    .map(([minute, value]) => ({ label: `${minute} 分`, value }))
}

function buildIntervalBuckets(samples) {
  if (!Array.isArray(samples) || samples.length === 0) {
    return []
  }
  const buckets = [
    { label: '0-15 秒', min: 0, max: 15, value: 0 },
    { label: '16-30 秒', min: 16, max: 30, value: 0 },
    { label: '31-60 秒', min: 31, max: 60, value: 0 },
    { label: '61-120 秒', min: 61, max: 120, value: 0 },
    { label: '120 秒以上', min: 121, max: Infinity, value: 0 }
  ]
  for (const sample of samples) {
    const interval = read(sample, 'interval_seconds', 'intervalSeconds') || 0
    const bucket = buckets.find((item) => interval >= item.min && interval <= item.max)
    if (bucket) {
      bucket.value += 1
    }
  }
  return buckets.filter((item) => item.value > 0)
}

function buildReasonBuckets(decisions) {
  if (!Array.isArray(decisions) || decisions.length === 0) {
    return []
  }
  const map = new Map()
  for (const decision of decisions) {
    const label = read(decision, 'takeaway') ? reasonLabel(read(decision, 'reason') || 'MODEL_TRIGGER') : '堂食决策'
    map.set(label, (map.get(label) || 0) + (read(decision, 'party_size', 'partySize') || 1))
  }
  return [...map.entries()].map(([label, value]) => ({ label, value }))
}

function reasonLabel(reason) {
  if (reason === 'TAKEAWAY_WINDOW') return '打包窗口'
  if (reason === 'NO_SEAT_SWITCH') return '无座转打包'
  if (reason === 'MODEL_TRIGGER') return '模型触发'
  if (reason === 'DINE_IN_MODEL') return '堂食模型'
  return reason || '模型记录'
}

export default ProbabilityPanel
