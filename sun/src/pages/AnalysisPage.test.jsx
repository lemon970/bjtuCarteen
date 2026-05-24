import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'

import AnalysisPage from './AnalysisPage'

describe('AnalysisPage RFC-011/012 集成', () => {
  it('summary 含 RFC-011/012 sub-DTO 时,3 个 panel 同时出现且顺序正确', () => {
    const report = {
      report_id: 'test-1',
      summary: {
        typical_wait_time_minutes: 4,
        wait_experience_proxy_metrics: {
          wait_experience_proxy_index: 0.42,
          pre_process_wait_share: 0.31,
          wait_uncertainty_score: 0.18,
          anxiety_pressure_index: 0.07,
          solo_adjusted_wait_minutes: 5.2,
          sample_count: 200
        },
        fairness_metrics: {
          wait_gini: 0.18,
          non_takeaway_window_load_cv: 0.22,
          cross_role_fairness: 1.4,
          sample_count: 200
        },
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
    const wait = screen.getByTestId('wait-experience-proxy-panel')
    const fairness = screen.getByTestId('fairness-panel')

    expect(bottleneck).toBeInTheDocument()
    expect(wait).toBeInTheDocument()
    expect(fairness).toBeInTheDocument()

    // 顺序:Bottleneck → WaitExperienceProxy → Fairness → WindowChoiceMetricsCard
    const order = bottleneck.compareDocumentPosition(wait)
    expect(order & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    const order2 = wait.compareDocumentPosition(fairness)
    expect(order2 & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('summary 缺少 RFC-011 sub-DTO 时,WaitExperience/Fairness 不渲染但 Bottleneck 仍渲染(BALANCED)', () => {
    const report = {
      report_id: 'test-2',
      summary: {
        typical_wait_time_minutes: 4,
        wait_experience_proxy_metrics: null,
        fairness_metrics: null,
        bottleneck_diagnosis: {
          primary: 'balanced',
          secondary: null,
          bottlenecks: []
        }
      },
      config: { duration: 0.05 }
    }
    render(<AnalysisPage report={report} />)
    expect(screen.queryByTestId('wait-experience-proxy-panel')).toBeNull()
    expect(screen.queryByTestId('fairness-panel')).toBeNull()
    expect(screen.getByTestId('bottleneck-diagnosis-panel')).toHaveAttribute('data-state', 'balanced')
  })
})
