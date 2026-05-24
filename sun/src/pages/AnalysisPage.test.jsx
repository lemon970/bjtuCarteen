import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'

import AnalysisPage from './AnalysisPage'

describe('AnalysisPage RFC-012 集成', () => {
  it('summary 含 bottleneck_diagnosis 时,瓶颈 panel 出现且位于 WindowChoiceMetricsCard 之前', () => {
    const report = {
      report_id: 'test-1',
      summary: {
        typical_wait_time_minutes: 4,
        bottleneck_diagnosis: {
          primary: 'balanced',
          secondary: null,
          bottlenecks: []
        },
        window_choice_metrics: {
          queue_choice_model: 'PREFERENCE_AWARE',
          popular_window_count: 2
        }
      },
      config: { duration: 0.5 }
    }
    render(<AnalysisPage report={report} />)

    const bottleneck = screen.getByTestId('bottleneck-diagnosis-panel')
    const windowChoice = screen.getByTestId('window-choice-metrics-card')
    expect(bottleneck).toBeInTheDocument()
    expect(windowChoice).toBeInTheDocument()
    const order = bottleneck.compareDocumentPosition(windowChoice)
    expect(order & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
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
})
