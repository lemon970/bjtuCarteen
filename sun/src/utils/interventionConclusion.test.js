import { describe, it, expect } from 'vitest'
import { buildConclusion, classifyImprovement } from './interventionConclusion'

function mkSummary(primary, wait, queue) {
  return {
    bottleneck_diagnosis: { primary, severity: 'HIGH' },
    typical_wait_time_minutes: wait,
    max_total_queue_size: queue
  }
}

describe('classifyImprovement 阈值分类', () => {
  it('≥0.15 = significant', () => {
    expect(classifyImprovement(0.20)).toBe('significant')
    expect(classifyImprovement(0.15)).toBe('significant')
  })
  it('0.05–0.15 = mild', () => {
    expect(classifyImprovement(0.10)).toBe('mild')
    expect(classifyImprovement(0.05)).toBe('mild')
  })
  it('0–0.05 = none', () => {
    expect(classifyImprovement(0.03)).toBe('none')
    expect(classifyImprovement(0)).toBe('none')
  })
  it('<0 = worse', () => {
    expect(classifyImprovement(-0.1)).toBe('worse')
  })
})

describe('buildConclusion', () => {
  it('takeaway_capacity 主导：+1 打包窗口大幅改善 → 显著改善', () => {
    const baseline = mkSummary('takeaway_capacity', 8, 50)
    const interventionResults = [
      { key: 'ADD_TAKEAWAY_WINDOW', status: 'ok', report: { summary: mkSummary('balanced', 5, 30) } }
    ]
    const text = buildConclusion({ baselineSummary: baseline, interventionResults })
    expect(text).toMatch(/显著改善|\+1 打包窗口/)
  })

  it('多个干预按 improvementScore 降序；恶化 -40% 不会被排到改善 +20% 前面', () => {
    const baseline = mkSummary('takeaway_capacity', 10, 50)
    const interventionResults = [
      { key: 'ADD_NORMAL_WINDOW', status: 'ok', report: { summary: mkSummary('takeaway_capacity', 14, 60) } },
      { key: 'ADD_TAKEAWAY_WINDOW', status: 'ok', report: { summary: mkSummary('balanced', 8, 40) } }
    ]
    const text = buildConclusion({ baselineSummary: baseline, interventionResults })
    const idxImprove = text.indexOf('+1 打包窗口')
    const idxWorse = text.indexOf('+1 普通窗口')
    expect(idxImprove).toBeGreaterThanOrEqual(0)
    expect(idxImprove).toBeLessThan(idxWorse === -1 ? Infinity : idxWorse)
  })

  it('arrival_surge 主导用 max_total_queue_size 作为关键 KPI', () => {
    const baseline = mkSummary('arrival_surge', 5, 100)
    const interventionResults = [
      { key: 'REDUCE_ARRIVAL', status: 'ok', report: { summary: mkSummary('balanced', 5, 50) } }
    ]
    const text = buildConclusion({ baselineSummary: baseline, interventionResults })
    expect(text).toMatch(/到达率/)
  })

  it('balanced 瓶颈下回退用 typical_wait_time_minutes', () => {
    const baseline = mkSummary('balanced', 4, 10)
    const interventionResults = [
      { key: 'ADD_SEATS', status: 'ok', report: { summary: mkSummary('balanced', 4, 10) } }
    ]
    expect(() => buildConclusion({ baselineSummary: baseline, interventionResults })).not.toThrow()
  })

  it('全部 status=error 时返回提示文本', () => {
    const baseline = mkSummary('takeaway_capacity', 8, 50)
    const interventionResults = [
      { key: 'ADD_TAKEAWAY_WINDOW', status: 'error', error: 'timeout' }
    ]
    const text = buildConclusion({ baselineSummary: baseline, interventionResults })
    expect(text).toMatch(/全部.*失败|无可用对照/)
  })

  it('primary 大写/null/`-` 都能 normalize', () => {
    const baseline1 = { bottleneck_diagnosis: { primary: 'TAKEAWAY-CAPACITY' }, typical_wait_time_minutes: 8 }
    const baseline2 = { bottleneck_diagnosis: { primary: null }, typical_wait_time_minutes: 8 }
    const r = [{ key: 'ADD_TAKEAWAY_WINDOW', status: 'ok', report: { summary: { typical_wait_time_minutes: 5 } } }]
    expect(() => buildConclusion({ baselineSummary: baseline1, interventionResults: r })).not.toThrow()
    expect(() => buildConclusion({ baselineSummary: baseline2, interventionResults: r })).not.toThrow()
  })

  it('intervention 后瓶颈换位会被结论提示', () => {
    const baseline = mkSummary('takeaway_capacity', 8, 50)
    const interventionResults = [
      { key: 'ADD_TAKEAWAY_WINDOW', status: 'ok', report: { summary: mkSummary('seat_capacity', 5, 30) } }
    ]
    const text = buildConclusion({ baselineSummary: baseline, interventionResults })
    expect(text).toMatch(/seat_capacity|座位/)
  })

  it('camelCase 字段也能识别', () => {
    const baseline = {
      bottleneckDiagnosis: { primary: 'takeaway_capacity' },
      typicalWaitTimeMinutes: 8
    }
    const interventionResults = [
      { key: 'ADD_TAKEAWAY_WINDOW', status: 'ok', report: { summary: { typicalWaitTimeMinutes: 5 } } }
    ]
    expect(() => buildConclusion({ baselineSummary: baseline, interventionResults })).not.toThrow()
  })
})
