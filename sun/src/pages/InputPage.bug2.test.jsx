/**
 * Bug-02 真实复现:加载 rain_emergency 预设后,点击 "运行当前配置" 按钮,
 * onRun 应被调用。
 *
 * 之前两次修复都靠 Java 镜像测试 (value/step ≈ integer, EPS=1e-6),
 * 绕过了浏览器、React、HTML5 form-validity、外置 button[form] 关联、
 * <details> 折叠区内非法控件这一整条真实路径。本测试直接 mount 真实
 * InputPage 组件,用真实的 rain_emergency SimConfig payload(后端 snake_case
 * 序列化的格式),走真实的 applyPayloadToForm,触发真实的 click 事件。
 *
 * 失败标准:onRun 调用次数 ≠ 1。
 * 这等价于用户人工观察到的"按钮失效"(点了没反应)。
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import InputPage from '../pages/InputPage'
import { applyPayloadToForm } from '../utils/simulation'

const RAIN_EMERGENCY_CONFIG = {
  simulation_name: '雨天应急预案',
  duration: 1.5,
  arrival_rate: 320,
  queue_limit: 45,
  pack_probability: 0.20,
  group_arrival_prob: 0.08,
  party_size: 3,
  walk_time_mean: 8.0,
  congestion_penalty: 0.35,
  seed: 20260604,
  base_config: {
    window_count: 9,
    takeaway_window_count: 2,
    takeaway_service_time_multiplier: 1.25,
    total_seats: 220,
    total_students: 1000
  },
  weather_config: {
    current_weather: 'rainy',
    weather_impact_factor: 1.25
  },
  random_bounds: {
    arrival_interval: 0,
    service_range: [55, 190],
    dining_range: [900, 2400],
    preference_range: [0.10, 0.50]
  },
  arrival_dist: { type: 'POISSON', lambda: 320 },
  normal_service_dist: { type: 'NORMAL', mean: 95, std: 22, min: 55, max: 190 },
  window_service_dist: { type: 'NORMAL', mean: 95, std: 22, min: 55, max: 190 },
  dining_time_dist: { type: 'NORMAL', mean: 1500, std: 250, min: 900, max: 2400 },
  peak_config: {
    class_peak_enabled: true,
    class_peak_start_minute: 12,
    class_peak_end_minute: 32,
    class_peak_multiplier: 3.25,
    class_peak_windows: [
      { start_minute: 12, end_minute: 32, multiplier: 3.25 },
      { start_minute: 64, end_minute: 86, multiplier: 2.25 }
    ]
  },
  group_config: {
    enabled: false,
    group_count: 0,
    size_min: 2,
    size_max: 4,
    arrival_spread_seconds: 0,
    behavior_correlation: 0.75,
    prefer_adjacent_seats: true
  }
}

function renderInputPage(overrides = {}) {
  const handlers = {
    onFieldChange: vi.fn(),
    onLoadScenario: vi.fn(),
    onToggleScenario: vi.fn(),
    onRunScenarioBatch: vi.fn(),
    onReset: vi.fn(),
    onRun: vi.fn((event) => event?.preventDefault?.()),
    onLoadLatest: vi.fn(),
    onFileUpload: vi.fn()
  }
  const form = applyPayloadToForm(RAIN_EMERGENCY_CONFIG)
  render(
    <InputPage
      form={form}
      loading={false}
      message=""
      scenarios={[]}
      selectedScenarioIds={[]}
      {...handlers}
      {...overrides}
    />
  )
  return { handlers, form }
}

describe('Bug-02 / rain_emergency preset must allow run button to submit form', () => {
  it('clicking 运行当前配置 after loading rain_emergency must call onRun exactly once', async () => {
    const user = userEvent.setup()
    const { handlers, form } = renderInputPage()

    expect(form.lunchPeakMultiplier).toBe(3.25)
    expect(form.dinnerPeakMultiplier).toBe(2.25)
    expect(form.weatherImpactFactor).toBe(1.25)
    expect(form.takeawayServiceTimeMultiplier).toBe(1.25)

    const runButton = screen.getByRole('button', { name: /运行当前配置/ })

    const formEl = document.getElementById('single-run-form')
    const invalids = []
    if (formEl) {
      for (const el of formEl.querySelectorAll('input')) {
        if (typeof el.checkValidity === 'function' && !el.checkValidity()) {
          invalids.push({
            type: el.type,
            value: el.value,
            min: el.min,
            max: el.max,
            step: el.step,
            stepMismatch: el.validity?.stepMismatch,
            rangeUnderflow: el.validity?.rangeUnderflow,
            rangeOverflow: el.validity?.rangeOverflow,
            badInput: el.validity?.badInput,
            valueMissing: el.validity?.valueMissing
          })
        }
      }
    }

    await user.click(runButton)

    expect(
      handlers.onRun.mock.calls.length,
      `Bug-02 reproduced: clicking 运行当前配置 with rain_emergency preset did not trigger onRun.\n`
        + `Form-level checkValidity invalids:\n`
        + JSON.stringify(invalids, null, 2)
    ).toBe(1)
  })
})
