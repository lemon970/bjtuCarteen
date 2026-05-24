import { read } from './simulation'

export const KPI_SPECS = [
  { key: 'typical_wait_time_minutes', altKey: 'typicalWaitTimeMinutes',
    label: '典型等待', direction: 'lower-better', unit: '分钟', precision: 1 },
  { key: 'max_total_queue_size', altKey: 'maxTotalQueueSize',
    label: '峰值排队', direction: 'lower-better', unit: '人', precision: 0 },
  { key: 'served_count', altKey: 'servedCount',
    label: '完成服务', direction: 'higher-better', unit: '人', precision: 0 },
  { key: 'takeaway_rate', altKey: 'takeawayRate',
    label: '打包率', direction: 'neutral', unit: '%', precision: 1, isPct: true },
  { key: 'seat_utilization_rate', altKey: 'seatUtilizationRate',
    label: '座位利用', direction: 'neutral', unit: '%', precision: 1, isPct: true }
]

const SIGNIFICANT_THRESHOLD = 0.05

export function computeDiff(baselineSummary, interventionSummary) {
  return KPI_SPECS.map(spec => {
    const a = read(baselineSummary, spec.key, spec.altKey)
    const b = read(interventionSummary, spec.key, spec.altKey)
    if (a == null || b == null) {
      return { ...spec, baseline: a, intervention: b, delta: null, pct: null, status: 'missing' }
    }
    const delta = b - a
    const pct = a !== 0 ? delta / Math.abs(a) : 0
    return { ...spec, baseline: a, intervention: b, delta, pct, status: 'ok' }
  })
}

export function classifyDirection(spec, pct) {
  if (!spec || spec.direction === 'neutral' || pct == null) return 'neutral'
  if (Math.abs(pct) < SIGNIFICANT_THRESHOLD) return 'neutral'
  const better = spec.direction === 'lower-better' ? pct < 0 : pct > 0
  return better ? 'better' : 'worse'
}

export function improvementScore(pct, direction) {
  if (pct == null) return 0
  if (direction === 'lower-better') return -pct
  if (direction === 'higher-better') return pct
  return 0
}
