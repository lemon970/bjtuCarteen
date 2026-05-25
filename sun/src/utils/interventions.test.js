import { describe, it, expect } from 'vitest'
import {
  INTERVENTIONS,
  FIDELITY_PRESETS,
  applyFidelity,
  normalizeBottleneckPrimary,
  roundDuration,
  MIN_DURATION_HOURS
} from './interventions'

describe('INTERVENTIONS apply 不可变', () => {
  it('apply 不修改原 form', () => {
    const form = { windowCount: 5, takeawayWindowCount: 1, totalSeats: 200, arrivalRate: 100 }
    const before = JSON.stringify(form)
    INTERVENTIONS.ADD_NORMAL_WINDOW.apply(form)
    INTERVENTIONS.ADD_TAKEAWAY_WINDOW.apply(form)
    INTERVENTIONS.ADD_SEATS.apply(form)
    INTERVENTIONS.REDUCE_ARRIVAL.apply(form)
    expect(JSON.stringify(form)).toBe(before)
  })
})

describe('INTERVENTIONS 4 类语义', () => {
  it('+1 普通窗口', () => {
    const r = INTERVENTIONS.ADD_NORMAL_WINDOW.apply({ windowCount: 5 })
    expect(r.windowCount).toBe(6)
  })
  it('+1 打包窗口', () => {
    const r = INTERVENTIONS.ADD_TAKEAWAY_WINDOW.apply({ takeawayWindowCount: 1 })
    expect(r.takeawayWindowCount).toBe(2)
  })
  it('+50 座位 clip 1000 上限', () => {
    expect(INTERVENTIONS.ADD_SEATS.apply({ totalSeats: 200 }).totalSeats).toBe(250)
    expect(INTERVENTIONS.ADD_SEATS.apply({ totalSeats: 980 }).totalSeats).toBe(1000)
  })
  it('到达率 -10% round', () => {
    expect(INTERVENTIONS.REDUCE_ARRIVAL.apply({ arrivalRate: 100 }).arrivalRate).toBe(90)
    expect(INTERVENTIONS.REDUCE_ARRIVAL.apply({ arrivalRate: 105 }).arrivalRate).toBe(95)
  })
})

describe('enabledIf 边界', () => {
  it('+1 普通窗口 < 20 允许', () => {
    expect(INTERVENTIONS.ADD_NORMAL_WINDOW.enabledIf({ windowCount: 19 })).toBe(true)
    expect(INTERVENTIONS.ADD_NORMAL_WINDOW.enabledIf({ windowCount: 20 })).toBe(false)
  })
  it('+1 打包窗口 < 5 允许', () => {
    expect(INTERVENTIONS.ADD_TAKEAWAY_WINDOW.enabledIf({ takeawayWindowCount: 4 })).toBe(true)
    expect(INTERVENTIONS.ADD_TAKEAWAY_WINDOW.enabledIf({ takeawayWindowCount: 5 })).toBe(false)
  })
  it('+50 座位 totalSeats=950 仍允许（可向上加 50 到 1000）', () => {
    expect(INTERVENTIONS.ADD_SEATS.enabledIf({ totalSeats: 950 })).toBe(true)
  })
  it('+50 座位 totalSeats=1000 禁用', () => {
    expect(INTERVENTIONS.ADD_SEATS.enabledIf({ totalSeats: 1000 })).toBe(false)
  })
  it('到达率 -10% arrivalRate>=50 允许', () => {
    expect(INTERVENTIONS.REDUCE_ARRIVAL.enabledIf({ arrivalRate: 50 })).toBe(true)
    expect(INTERVENTIONS.REDUCE_ARRIVAL.enabledIf({ arrivalRate: 49 })).toBe(false)
  })
})

describe('primaryFor 自动预选查表', () => {
  it('每个干预 primaryFor 是非空 string 数组', () => {
    Object.values(INTERVENTIONS).forEach(iv => {
      expect(Array.isArray(iv.primaryFor)).toBe(true)
      expect(iv.primaryFor.length).toBeGreaterThan(0)
    })
  })
})

describe('summary 文案', () => {
  it('summary 返回 string', () => {
    const form = { windowCount: 5, takeawayWindowCount: 1, totalSeats: 200, arrivalRate: 100 }
    Object.values(INTERVENTIONS).forEach(iv => {
      expect(typeof iv.summary(form)).toBe('string')
    })
  })
})

describe('fidelity', () => {
  it('FIDELITY_PRESETS 两档（v3 起 fast 档已下线）', () => {
    expect(FIDELITY_PRESETS.full.multiplier).toBe(1.0)
    expect(FIDELITY_PRESETS.preview.multiplier).toBe(0.5)
    expect(FIDELITY_PRESETS.fast).toBeUndefined()
  })
  it('applyFidelity 缩放 duration', () => {
    expect(applyFidelity({ duration: 2 }, 'full').duration).toBe(2)
    expect(applyFidelity({ duration: 2 }, 'preview').duration).toBe(1)
  })
  it('applyFidelity 守 MIN_DURATION_HOURS 下限', () => {
    expect(applyFidelity({ duration: 0.05 }, 'preview').duration).toBe(MIN_DURATION_HOURS)
  })
  it('applyFidelity 未知 key 回退 full（含已下线的 fast）', () => {
    expect(applyFidelity({ duration: 2 }, 'unknown').duration).toBe(2)
    expect(applyFidelity({ duration: 2 }, 'fast').duration).toBe(2)
  })
  it('roundDuration 保留 2 位小数', () => {
    expect(roundDuration(1.234)).toBe(1.23)
    expect(roundDuration(0.001)).toBe(MIN_DURATION_HOURS)
  })
})

describe('normalizeBottleneckPrimary', () => {
  it('null/undefined → balanced', () => {
    expect(normalizeBottleneckPrimary(null)).toBe('balanced')
    expect(normalizeBottleneckPrimary(undefined)).toBe('balanced')
  })
  it('大写 → 小写', () => {
    expect(normalizeBottleneckPrimary('TAKEAWAY_CAPACITY')).toBe('takeaway_capacity')
  })
  it('短横线 → 下划线', () => {
    expect(normalizeBottleneckPrimary('window-service-capacity')).toBe('window_service_capacity')
  })
  it('两端空格 trim', () => {
    expect(normalizeBottleneckPrimary('  seat_capacity  ')).toBe('seat_capacity')
  })
})

// 已知缺陷捕获：v1 applyFidelity 只缩 duration，不缩 peak 窗口。
// 用"午高峰压力测试"预设参数（duration=2h, lunchPeakStart=30, lunchPeakEnd=90）
// 演示当前实现破坏场景形状的根因。手测现象：完整档与预览档对照同一干预出现
// sign flip，因为 fast 档下 30 min 仿真还没到 30 min 的 lunchPeakStart，
// 峰值完全不触发；preview 档下峰值被截断、warmup 占比翻倍，场景已不同。
// 修复目标：peak 窗口按 fidelity multiplier 同比缩放，保持场景"时间形状"。
describe('applyFidelity 应保持场景形状（午高峰压力测试场景）', () => {
  const lunchPeakForm = {
    duration: 2,
    lunchPeakStart: 30,
    lunchPeakEnd: 90,
    dinnerPeakStart: 720,
    dinnerPeakEnd: 780,
    arrivalRate: 300,
    peakEnabled: true
  }

  it('preview 档：lunchPeakStart 应跟随 duration × 0.5 缩放', () => {
    const r = applyFidelity(lunchPeakForm, 'preview')
    expect(r.duration).toBe(1)
    expect(r.lunchPeakStart).toBe(15)
    expect(r.lunchPeakEnd).toBe(45)
  })

  it('preview 档：dinnerPeak 也应同比缩放', () => {
    const r = applyFidelity(lunchPeakForm, 'preview')
    expect(r.dinnerPeakStart).toBe(360)
    expect(r.dinnerPeakEnd).toBe(390)
  })

  it('preview 档：lunch 峰值占总时长比例应与 full 档保持一致', () => {
    const full = applyFidelity(lunchPeakForm, 'full')
    const preview = applyFidelity(lunchPeakForm, 'preview')
    const fullRatio = (full.lunchPeakEnd - full.lunchPeakStart) / (full.duration * 60)
    const previewRatio = (preview.lunchPeakEnd - preview.lunchPeakStart) / (preview.duration * 60)
    expect(previewRatio).toBeCloseTo(fullRatio, 2)
  })

  it('preview 档：warmup 占比（峰值起点 / 总时长）应保持一致', () => {
    const full = applyFidelity(lunchPeakForm, 'full')
    const preview = applyFidelity(lunchPeakForm, 'preview')
    const fullWarmup = full.lunchPeakStart / (full.duration * 60)
    const previewWarmup = preview.lunchPeakStart / (preview.duration * 60)
    expect(previewWarmup).toBeCloseTo(fullWarmup, 2)
  })

  it('full 档：peak 字段不应被改动', () => {
    const r = applyFidelity(lunchPeakForm, 'full')
    expect(r.lunchPeakStart).toBe(30)
    expect(r.lunchPeakEnd).toBe(90)
    expect(r.dinnerPeakStart).toBe(720)
    expect(r.dinnerPeakEnd).toBe(780)
  })

  it('peak 字段缺失时不抛错，只缩 duration', () => {
    const minimal = { duration: 2 }
    expect(() => applyFidelity(minimal, 'preview')).not.toThrow()
    expect(applyFidelity(minimal, 'preview').duration).toBe(1)
  })
})
