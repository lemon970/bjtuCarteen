import { describe, it, expect } from 'vitest'

import { applyPayloadToForm, buildPayload } from './simulation'
import { DEFAULT_FORM } from '../constants'

describe('RFC-009 PR-9D queue_choice_model 前端入口', () => {
  it('默认 form 在 base_config 写入 STATIC_SPLIT 且不发送 window_attractiveness', () => {
    const payload = buildPayload({ ...DEFAULT_FORM })
    expect(payload.base_config.queue_choice_model).toBe('STATIC_SPLIT')
    expect(payload.base_config.window_attractiveness).toBeUndefined()
  })

  it('queueChoiceModel === PREFERENCE_AWARE 时把 5 个吸引力字段写入 base_config', () => {
    const payload = buildPayload({
      ...DEFAULT_FORM,
      queueChoiceModel: 'PREFERENCE_AWARE',
      popularWindowRatio: 0.3,
      coldWindowRatio: 0.2,
      popularAttractiveness: 1.6,
      normalAttractiveness: 1.1,
      coldAttractiveness: 0.7
    })
    expect(payload.base_config.queue_choice_model).toBe('PREFERENCE_AWARE')
    const attr = payload.base_config.window_attractiveness
    expect(attr).toBeDefined()
    expect(attr.popular_window_ratio).toBe(0.3)
    expect(attr.cold_window_ratio).toBe(0.2)
    expect(attr.popular_attractiveness).toBe(1.6)
    expect(attr.normal_attractiveness).toBe(1.1)
    expect(attr.cold_attractiveness).toBe(0.7)
  })

  it('PREFERENCE_AWARE 下窗口比例越界会被 clamp 到 [0, 1]', () => {
    const payload = buildPayload({
      ...DEFAULT_FORM,
      queueChoiceModel: 'PREFERENCE_AWARE',
      popularWindowRatio: 1.5,
      coldWindowRatio: -0.2
    })
    const attr = payload.base_config.window_attractiveness
    expect(attr.popular_window_ratio).toBe(1)
    expect(attr.cold_window_ratio).toBe(0)
  })

  it('未识别的 queueChoiceModel 视为 STATIC_SPLIT,不发送 window_attractiveness', () => {
    const payload = buildPayload({ ...DEFAULT_FORM, queueChoiceModel: 'INVALID_UNKNOWN_VALUE' })
    expect(payload.base_config.queue_choice_model).toBe('STATIC_SPLIT')
    expect(payload.base_config.window_attractiveness).toBeUndefined()
  })

  it('applyPayloadToForm 能从 snake_case base_config 回填 PREFERENCE_AWARE 字段', () => {
    const payload = {
      base_config: {
        window_count: 8,
        takeaway_window_count: 1,
        total_seats: 200,
        total_students: 800,
        queue_choice_model: 'PREFERENCE_AWARE',
        window_attractiveness: {
          popular_window_ratio: 0.3,
          cold_window_ratio: 0.15,
          popular_attractiveness: 1.5,
          normal_attractiveness: 1.0,
          cold_attractiveness: 0.7
        }
      }
    }
    const form = applyPayloadToForm(payload)
    expect(form.queueChoiceModel).toBe('PREFERENCE_AWARE')
    expect(form.popularWindowRatio).toBe(0.3)
    expect(form.coldWindowRatio).toBe(0.15)
    expect(form.popularAttractiveness).toBe(1.5)
    expect(form.normalAttractiveness).toBe(1.0)
    expect(form.coldAttractiveness).toBe(0.7)
  })

  it('applyPayloadToForm 能从 camelCase base_config 回填', () => {
    const payload = {
      baseConfig: {
        queueChoiceModel: 'PREFERENCE_AWARE',
        windowAttractiveness: {
          popularWindowRatio: 0.4,
          coldWindowRatio: 0.1,
          popularAttractiveness: 1.7,
          normalAttractiveness: 1.0,
          coldAttractiveness: 0.6
        }
      }
    }
    const form = applyPayloadToForm(payload)
    expect(form.queueChoiceModel).toBe('PREFERENCE_AWARE')
    expect(form.popularWindowRatio).toBe(0.4)
    expect(form.popularAttractiveness).toBe(1.7)
  })

  it('旧报告 base_config 缺失 queue_choice_model 时回退到 STATIC_SPLIT 默认', () => {
    const payload = { base_config: { window_count: 4 } }
    const form = applyPayloadToForm(payload)
    expect(form.queueChoiceModel).toBe('STATIC_SPLIT')
    expect(form.popularWindowRatio).toBe(DEFAULT_FORM.popularWindowRatio)
    expect(form.coldAttractiveness).toBe(DEFAULT_FORM.coldAttractiveness)
  })

  it('queue_choice_model = INVALID_UNKNOWN_VALUE 等未知值在回填时降级到 STATIC_SPLIT', () => {
    const payload = { base_config: { queue_choice_model: 'INVALID_UNKNOWN_VALUE' } }
    const form = applyPayloadToForm(payload)
    expect(form.queueChoiceModel).toBe('STATIC_SPLIT')
  })
})
