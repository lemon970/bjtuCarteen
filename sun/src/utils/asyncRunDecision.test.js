import { describe, it, expect } from 'vitest'

import { decideRunMode } from './asyncRunDecision'

describe('decideRunMode', () => {
  it('userToggle=sync 强制返回 sync,即使 heuristic 应该走 async', () => {
    const form = { duration: 10, arrivalRate: 5000, totalStudents: 0 }
    expect(decideRunMode(form, 'sync')).toBe('sync')
  })

  it('userToggle=async 强制返回 async,即使 heuristic 应该走 sync', () => {
    const form = { duration: 0.1, arrivalRate: 10, totalStudents: 100 }
    expect(decideRunMode(form, 'async')).toBe('async')
  })

  it('auto + estimatedArrivals < 8000 + duration < 4 → sync', () => {
    const form = { duration: 2, arrivalRate: 1500, totalStudents: 999999 }
    expect(decideRunMode(form, 'auto')).toBe('sync')
  })

  it('auto + duration*arrivalRate >= 8000 → async', () => {
    const form = { duration: 2, arrivalRate: 5000, totalStudents: 999999 }
    expect(decideRunMode(form, 'auto')).toBe('async')
  })

  it('auto + totalStudents 截断使 estimatedArrivals < 8000 → sync', () => {
    const form = { duration: 2, arrivalRate: 5000, totalStudents: 3000 }
    expect(decideRunMode(form, 'auto')).toBe('sync')
  })

  it('auto + totalStudents=0 视为无上限,raw=duration*arrivalRate 触发 async', () => {
    const form = { duration: 2, arrivalRate: 5000, totalStudents: 0 }
    expect(decideRunMode(form, 'auto')).toBe('async')
  })

  it('auto + duration >= 4 兜底触发 async,即使 arrivals < 8000', () => {
    const form = { duration: 5, arrivalRate: 100, totalStudents: 0 }
    expect(decideRunMode(form, 'auto')).toBe('async')
  })

  it('auto + 字段缺失或 NaN 不抛异常,默认 sync', () => {
    expect(decideRunMode({}, 'auto')).toBe('sync')
    expect(decideRunMode({ duration: 'x', arrivalRate: null }, 'auto')).toBe('sync')
  })

  it('未知 userToggle 视为 auto', () => {
    const form = { duration: 2, arrivalRate: 5000, totalStudents: 0 }
    expect(decideRunMode(form, 'whatever')).toBe('async')
  })
})
