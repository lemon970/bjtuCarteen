import { read } from './simulation'
import { INTERVENTIONS, normalizeBottleneckPrimary } from './interventions'
import { improvementScore } from './interventionDiff'

const SIGNIFICANT = 0.15
const MILD = 0.05

const PRIMARY_KPI_BY_BOTTLENECK = {
  window_service_capacity: { key: 'typical_wait_time_minutes', altKey: 'typicalWaitTimeMinutes', direction: 'lower-better' },
  takeaway_capacity:       { key: 'typical_wait_time_minutes', altKey: 'typicalWaitTimeMinutes', direction: 'lower-better' },
  seat_capacity:           { key: 'typical_wait_time_minutes', altKey: 'typicalWaitTimeMinutes', direction: 'lower-better' },
  arrival_surge:           { key: 'max_total_queue_size',      altKey: 'maxTotalQueueSize',      direction: 'lower-better' }
}

const DEFAULT_KPI = { key: 'typical_wait_time_minutes', altKey: 'typicalWaitTimeMinutes', direction: 'lower-better' }

const LABEL_BY_CLASS = {
  significant: '显著改善',
  mild: '轻微改善',
  none: '无明显差异',
  worse: '反而恶化'
}

export function classifyImprovement(score) {
  if (score >= SIGNIFICANT) return 'significant'
  if (score >= MILD) return 'mild'
  if (score >= 0) return 'none'
  return 'worse'
}

export function buildConclusion({ baselineSummary, interventionResults }) {
  const primaryRaw = read(baselineSummary, 'bottleneck_diagnosis', 'bottleneckDiagnosis')?.primary
  const primary = normalizeBottleneckPrimary(primaryRaw)
  const kpiSpec = PRIMARY_KPI_BY_BOTTLENECK[primary] || DEFAULT_KPI

  const ok = interventionResults.filter(r => r.status === 'ok')
  if (ok.length === 0) {
    return '本批次干预全部失败，无可用对照。'
  }

  const a = read(baselineSummary, kpiSpec.key, kpiSpec.altKey)
  const ranked = ok.map(r => {
    const b = read(r.report.summary, kpiSpec.key, kpiSpec.altKey)
    const pct = (a != null && b != null && a !== 0) ? (b - a) / Math.abs(a) : null
    const score = improvementScore(pct, kpiSpec.direction)
    const interventionPrimary = normalizeBottleneckPrimary(
      read(r.report.summary, 'bottleneck_diagnosis', 'bottleneckDiagnosis')?.primary
    )
    return {
      key: r.key,
      label: INTERVENTIONS[r.key]?.label ?? r.key,
      pct,
      score,
      cls: classifyImprovement(score),
      bAfter: b,
      interventionPrimary,
      bottleneckShifted: interventionPrimary !== primary
        && interventionPrimary !== 'balanced'
        && primary !== 'balanced'
    }
  }).sort((x, y) => y.score - x.score)

  const top = ranked[0]
  const aText = a != null ? Number(a).toFixed(1) : '?'
  const bText = top.bAfter != null ? Number(top.bAfter).toFixed(1) : '?'
  const pctText = top.pct != null ? `（${(top.pct * 100).toFixed(0)}%）` : ''
  const primaryText = primary === 'balanced' ? '系统均衡' : primary.toUpperCase()

  let text = `针对当前瓶颈 ${primaryText}，${top.label} 使关键指标从 ${aText} → ${bText}${pctText}，${LABEL_BY_CLASS[top.cls]}。`
  if (top.bottleneckShifted) {
    text += ` 注意：消解后瓶颈转为 ${top.interventionPrimary}，需关注。`
  }
  if (ranked.length > 1) {
    const second = ranked[1]
    text += ` ${second.label} ${LABEL_BY_CLASS[second.cls]}。`
  }
  return text
}
