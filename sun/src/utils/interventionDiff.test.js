import { describe, it, expect } from 'vitest'
import { computeDiff, classifyDirection, improvementScore, KPI_SPECS } from './interventionDiff'

describe('computeDiff', () => {
  it('正常 delta 与 pct', () => {
    const baseline = { typical_wait_time_minutes: 8, max_total_queue_size: 50 }
    const intervention = { typical_wait_time_minutes: 5, max_total_queue_size: 30 }
    const diff = computeDiff(baseline, intervention)
    const wait = diff.find(d => d.key === 'typical_wait_time_minutes')
    expect(wait.delta).toBeCloseTo(-3)
    expect(wait.pct).toBeCloseTo(-3 / 8)
    expect(wait.status).toBe('ok')
  })

  it('字段缺失返回 status=missing 不展开 0', () => {
    const baseline = { typical_wait_time_minutes: 8 }
    const intervention = { typical_wait_time_minutes: 5 }
    const diff = computeDiff(baseline, intervention)
    const queue = diff.find(d => d.key === 'max_total_queue_size')
    expect(queue.status).toBe('missing')
    expect(queue.delta).toBeNull()
    expect(queue.pct).toBeNull()
  })

  it('零分母 pct=0 不抛 NaN', () => {
    const baseline = { typical_wait_time_minutes: 0 }
    const intervention = { typical_wait_time_minutes: 5 }
    const diff = computeDiff(baseline, intervention)
    const wait = diff.find(d => d.key === 'typical_wait_time_minutes')
    expect(wait.pct).toBe(0)
    expect(Number.isFinite(wait.delta)).toBe(true)
  })

  it('支持 camelCase altKey', () => {
    const baseline = { typicalWaitTimeMinutes: 8 }
    const intervention = { typicalWaitTimeMinutes: 5 }
    const diff = computeDiff(baseline, intervention)
    const wait = diff.find(d => d.key === 'typical_wait_time_minutes')
    expect(wait.status).toBe('ok')
    expect(wait.delta).toBeCloseTo(-3)
  })
})

describe('classifyDirection', () => {
  it('lower-better 降低 ≥5% → better', () => {
    const spec = KPI_SPECS.find(s => s.key === 'typical_wait_time_minutes')
    expect(classifyDirection(spec, -0.10)).toBe('better')
  })
  it('lower-better 升高 ≥5% → worse', () => {
    const spec = KPI_SPECS.find(s => s.key === 'typical_wait_time_minutes')
    expect(classifyDirection(spec, 0.10)).toBe('worse')
  })
  it('higher-better 升高 ≥5% → better', () => {
    const spec = KPI_SPECS.find(s => s.key === 'served_count')
    expect(classifyDirection(spec, 0.10)).toBe('better')
  })
  it('5% 阈值内 neutral', () => {
    const spec = KPI_SPECS.find(s => s.key === 'typical_wait_time_minutes')
    expect(classifyDirection(spec, -0.04)).toBe('neutral')
    expect(classifyDirection(spec, 0.04)).toBe('neutral')
  })
  it('neutral 方向永远 neutral', () => {
    const spec = KPI_SPECS.find(s => s.key === 'takeaway_rate')
    expect(classifyDirection(spec, -0.5)).toBe('neutral')
  })
  it('pct=null → neutral', () => {
    const spec = KPI_SPECS.find(s => s.key === 'typical_wait_time_minutes')
    expect(classifyDirection(spec, null)).toBe('neutral')
  })
})

describe('improvementScore 方向感知', () => {
  it('lower-better 改善（pct<0）分数为正且数值与 abs(pct) 一致', () => {
    expect(improvementScore(-0.20, 'lower-better')).toBeCloseTo(0.20)
  })
  it('lower-better 恶化（pct>0）分数为负', () => {
    expect(improvementScore(0.40, 'lower-better')).toBeCloseTo(-0.40)
  })
  it('higher-better 改善（pct>0）分数为正', () => {
    expect(improvementScore(0.20, 'higher-better')).toBeCloseTo(0.20)
  })
  it('neutral 永远 0', () => {
    expect(improvementScore(0.50, 'neutral')).toBe(0)
  })
  it('null 返回 0', () => {
    expect(improvementScore(null, 'lower-better')).toBe(0)
  })
})
