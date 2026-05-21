import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'

import WindowChoiceMetricsCard from './WindowChoiceMetricsCard'

describe('WindowChoiceMetricsCard', () => {
  it('summary.window_choice_metrics 缺失时不渲染', () => {
    const { container } = render(<WindowChoiceMetricsCard summary={{}} />)
    expect(container.firstChild).toBeNull()
  })

  it('window_choice_metrics 为 null 时不渲染', () => {
    const { container } = render(
      <WindowChoiceMetricsCard summary={{ window_choice_metrics: null }} />
    )
    expect(container.firstChild).toBeNull()
  })

  it('完整 metrics 时渲染 popular/normal/cold 份额与平均等待', () => {
    const summary = {
      window_choice_metrics: {
        queue_choice_model: 'PREFERENCE_AWARE',
        popular_window_count: 2,
        normal_window_count: 4,
        cold_window_count: 2,
        takeaway_window_count: 2,
        popular_preference_share: 0.34,
        normal_preference_share: 0.47,
        cold_preference_share: 0.19,
        popular_served_share: 0.36,
        normal_served_share: 0.50,
        cold_served_share: 0.14,
        popular_avg_wait_minutes: 3.4,
        normal_avg_wait_minutes: 1.9,
        cold_avg_wait_minutes: 0.8,
        max_window_queue_gap: 6,
        window_served_count_cv: 0.38
      }
    }
    render(<WindowChoiceMetricsCard summary={summary} />)
    expect(screen.getByTestId('window-choice-metrics-card')).toBeInTheDocument()
    expect(screen.getByText('PREFERENCE_AWARE')).toBeInTheDocument()
    // 三档 preference share(formatPercent → 34.0% / 47.0% / 19.0%)
    expect(screen.getByText('34.0%')).toBeInTheDocument()
    expect(screen.getByText('47.0%')).toBeInTheDocument()
    expect(screen.getByText('19.0%')).toBeInTheDocument()
    // 三档平均等待
    expect(screen.getByText('3.40 分')).toBeInTheDocument()
    expect(screen.getByText('1.90 分')).toBeInTheDocument()
    expect(screen.getByText('0.80 分')).toBeInTheDocument()
    // CV / max gap
    expect(screen.getByText('0.380')).toBeInTheDocument()
    expect(screen.getByText('6')).toBeInTheDocument()
  })

  it('字段缺失或非数值时显示 -,不报错', () => {
    const summary = {
      window_choice_metrics: {
        queue_choice_model: 'PREFERENCE_AWARE',
        popular_window_count: 2,
        // 故意只放一部分字段;其余为 undefined / 非数值
        popular_preference_share: 'not-a-number',
        normal_avg_wait_minutes: null
      }
    }
    expect(() => render(<WindowChoiceMetricsCard summary={summary} />)).not.toThrow()
    expect(screen.getByTestId('window-choice-metrics-card')).toBeInTheDocument()
    // 非数值的 share 字段应渲染 '-'
    const dashes = screen.getAllByText('-')
    expect(dashes.length).toBeGreaterThan(0)
  })

  it('camelCase metrics 也能渲染(applyPayloadToForm 之后的兼容路径)', () => {
    const summary = {
      windowChoiceMetrics: {
        queueChoiceModel: 'PREFERENCE_AWARE',
        popularWindowCount: 2,
        popularPreferenceShare: 0.5
      }
    }
    render(<WindowChoiceMetricsCard summary={summary} />)
    expect(screen.getByTestId('window-choice-metrics-card')).toBeInTheDocument()
    expect(screen.getByText('PREFERENCE_AWARE')).toBeInTheDocument()
    expect(screen.getByText('50.0%')).toBeInTheDocument()
  })
})
