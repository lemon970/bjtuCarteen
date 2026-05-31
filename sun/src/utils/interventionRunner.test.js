import { describe, it, expect, vi } from 'vitest'
import { runInterventions } from './interventionRunner'

const baseForm = { duration: 1, windowCount: 5, takeawayWindowCount: 1, totalSeats: 200, arrivalRate: 100 }

describe('runInterventions', () => {
  it('正常 run 1+N 次，baseline + interventions 都 status=ok', async () => {
    const runFn = vi.fn(async (form) => ({ summary: { typical_wait_time_minutes: form.windowCount }, config: form }))
    const result = await runInterventions({
      baselineForm: baseForm,
      fidelityKey: 'full',
      interventionKeys: ['ADD_NORMAL_WINDOW', 'ADD_TAKEAWAY_WINDOW'],
      runFn
    })
    expect(runFn).toHaveBeenCalledTimes(3)
    expect(result.baseline.status).toBe('ok')
    expect(result.interventions).toHaveLength(2)
    expect(result.interventions.every(r => r.status === 'ok')).toBe(true)
  })

  it('baseline 失败短路，interventions 不跑', async () => {
    const runFn = vi.fn(async () => { throw new Error('baseline crashed') })
    const result = await runInterventions({
      baselineForm: baseForm,
      fidelityKey: 'full',
      interventionKeys: ['ADD_NORMAL_WINDOW'],
      runFn
    })
    expect(runFn).toHaveBeenCalledTimes(1)
    expect(result.baseline.status).toBe('error')
    expect(result.baseline.error).toContain('baseline crashed')
    expect(result.interventions).toEqual([])
  })

  it('intervention 失败隔离，后续 intervention 仍跑', async () => {
    let n = 0
    const runFn = vi.fn(async () => {
      n++
      if (n === 2) throw new Error('intervention 1 failed')
      return { summary: { typical_wait_time_minutes: 5 } }
    })
    const result = await runInterventions({
      baselineForm: baseForm,
      fidelityKey: 'full',
      interventionKeys: ['ADD_NORMAL_WINDOW', 'ADD_TAKEAWAY_WINDOW'],
      runFn
    })
    expect(runFn).toHaveBeenCalledTimes(3)
    expect(result.baseline.status).toBe('ok')
    expect(result.interventions[0].status).toBe('error')
    expect(result.interventions[0].error).toContain('intervention 1 failed')
    expect(result.interventions[1].status).toBe('ok')
  })

  it('progress 回调按顺序触发 baseline → 各 intervention key', async () => {
    const calls = []
    const runFn = vi.fn(async () => ({ summary: {} }))
    await runInterventions({
      baselineForm: baseForm,
      fidelityKey: 'full',
      interventionKeys: ['ADD_NORMAL_WINDOW', 'ADD_SEATS'],
      runFn,
      onProgress: (p) => calls.push(p)
    })
    expect(calls.map(c => c.stage)).toEqual(['baseline', 'ADD_NORMAL_WINDOW', 'ADD_SEATS'])
    expect(calls.map(c => c.index)).toEqual([1, 2, 3])
    expect(calls.every(c => c.total === 3)).toBe(true)
  })

  it('fidelity 影响 baseline 与所有 intervention 的 duration', async () => {
    const runFn = vi.fn(async (form) => ({ summary: {}, config: form }))
    await runInterventions({
      baselineForm: { ...baseForm, duration: 2 },
      fidelityKey: 'preview',
      interventionKeys: ['ADD_NORMAL_WINDOW'],
      runFn
    })
    runFn.mock.calls.forEach(call => {
      expect(call[0].duration).toBe(1)
    })
  })
})
