import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'

import FairnessPanel from './FairnessPanel'

describe('FairnessPanel', () => {
  it('summary.fairness_metrics 为 null 时整 panel 不渲染', () => {
    const { container } = render(<FairnessPanel summary={{ fairness_metrics: null }} />)
    expect(container.firstChild).toBeNull()
  })

  it('完整 metrics 时渲染 4 项与 cross_role 字段', () => {
    const summary = {
      fairness_metrics: {
        wait_gini: 0.18,
        non_takeaway_window_load_cv: 0.22,
        cross_role_fairness: 1.4,
        sample_count: 428
      }
    }
    render(<FairnessPanel summary={summary} />)
    expect(screen.getByTestId('fairness-panel')).toBeInTheDocument()
    expect(screen.getByText('等待 GINI')).toBeInTheDocument()
    expect(screen.getByText('0.180')).toBeInTheDocument()
    expect(screen.getByText('非打包窗口负载 CV')).toBeInTheDocument()
    expect(screen.getByText('0.220')).toBeInTheDocument()
    expect(screen.getByText('跨角色差异')).toBeInTheDocument()
    expect(screen.getByText('1.40 分钟')).toBeInTheDocument()
    expect(screen.getByText('428')).toBeInTheDocument()
  })

  it('Gini 阈值上色:0.19 绿、0.20 黄、0.39 黄、0.40 红', () => {
    const cases = [
      { gini: 0.19, expected: 'fairness-status-ok' },
      { gini: 0.20, expected: 'fairness-status-warn' },
      { gini: 0.39, expected: 'fairness-status-warn' },
      { gini: 0.40, expected: 'fairness-status-bad' }
    ]
    for (const { gini, expected } of cases) {
      const { unmount } = render(
        <FairnessPanel
          summary={{
            fairness_metrics: {
              wait_gini: gini,
              non_takeaway_window_load_cv: 0,
              cross_role_fairness: 0,
              sample_count: 100
            }
          }}
        />
      )
      const giniRow = screen.getByTestId('fairness-row-gini')
      expect(giniRow).toHaveAttribute('data-status', expected)
      unmount()
    }
  })

  it('camelCase 与 0 值兼容', () => {
    const summary = {
      fairnessMetrics: {
        waitGini: 0,
        nonTakeawayWindowLoadCv: 0,
        crossRoleFairness: 0,
        sampleCount: 50
      }
    }
    render(<FairnessPanel summary={summary} />)
    expect(screen.getByTestId('fairness-panel')).toBeInTheDocument()
    expect(screen.getAllByText('0.000').length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText('0.00 分钟')).toBeInTheDocument()
    expect(screen.getByText('50')).toBeInTheDocument()
    // 全 0 时三项都是绿色
    expect(screen.getByTestId('fairness-row-gini')).toHaveAttribute('data-status', 'fairness-status-ok')
    expect(screen.getByTestId('fairness-row-cv')).toHaveAttribute('data-status', 'fairness-status-ok')
    expect(screen.getByTestId('fairness-row-cross-role')).toHaveAttribute('data-status', 'fairness-status-ok')
  })
})
