import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'

import AnalysisPage from './AnalysisPage'

describe('AnalysisPage RFC-012 集成', () => {
  it('summary 含 bottleneck_diagnosis 时,瓶颈 panel 出现', () => {
    const report = {
      report_id: 'test-1',
      summary: {
        typical_wait_time_minutes: 4,
        bottleneck_diagnosis: {
          primary: 'balanced',
          secondary: null,
          bottlenecks: []
        }
      },
      config: { duration: 0.5 }
    }
    render(<AnalysisPage report={report} />)

    const bottleneck = screen.getByTestId('bottleneck-diagnosis-panel')
    expect(bottleneck).toBeInTheDocument()
  })

  it('summary.bottleneck_diagnosis 为 null 时,瓶颈 panel 不渲染但不抛错', () => {
    const report = {
      report_id: 'test-2',
      summary: {
        typical_wait_time_minutes: 4,
        bottleneck_diagnosis: null
      },
      config: { duration: 0.05 }
    }
    expect(() => render(<AnalysisPage report={report} />)).not.toThrow()
    expect(screen.queryByTestId('bottleneck-diagnosis-panel')).toBeNull()
  })

  it('takeaway_rate_breakdown 存在时,渲染打包决策结论模块,主导项被高亮', () => {
    const report = {
      report_id: 'test-3',
      summary: {
        typical_wait_time_minutes: 4,
        takeaway_rate: 0.5,
        takeaway_rate_breakdown: {
          initial_intent_rate: 0.4,
          dynamic_flip_rate: 0.05,
          no_seat_forced_rate: 0.05,
          observed_rate: 0.5,
          theoretical_rate: 0.45
        }
      },
      config: { duration: 0.5 }
    }
    render(<AnalysisPage report={report} />)
    expect(screen.getByText('打包决策结论')).toBeInTheDocument()
    expect(screen.getByText(/主导成因:初始意图/)).toBeInTheDocument()
  })

  it('takeaway_rate_breakdown 为空时,显示空态而不是表格', () => {
    const report = {
      report_id: 'test-4',
      summary: {
        typical_wait_time_minutes: 4
      },
      config: { duration: 0.5 }
    }
    render(<AnalysisPage report={report} />)
    expect(screen.getByText('本次仿真未触发打包决策样本。')).toBeInTheDocument()
  })
})
