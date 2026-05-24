import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'

import WaitExperienceProxyPanel from './WaitExperienceProxyPanel'

describe('WaitExperienceProxyPanel', () => {
  it('summary.wait_experience_proxy_metrics 为 null 时整 panel 不渲染', () => {
    const { container } = render(
      <WaitExperienceProxyPanel summary={{ wait_experience_proxy_metrics: null }} />
    )
    expect(container.firstChild).toBeNull()
  })

  it('完整 metrics 时渲染 6 个展示项', () => {
    const summary = {
      wait_experience_proxy_metrics: {
        wait_experience_proxy_index: 0.42,
        pre_process_wait_share: 0.31,
        wait_uncertainty_score: 0.18,
        anxiety_pressure_index: 0.07,
        solo_adjusted_wait_minutes: 5.2,
        sample_count: 428
      }
    }
    render(<WaitExperienceProxyPanel summary={summary} />)
    const panel = screen.getByTestId('wait-experience-proxy-panel')
    expect(panel).toBeInTheDocument()

    expect(screen.getByText('综合代理指数')).toBeInTheDocument()
    expect(screen.getByText('0.420')).toBeInTheDocument()
    expect(screen.getByText('P/I 比')).toBeInTheDocument()
    expect(screen.getByText('0.310')).toBeInTheDocument()
    expect(screen.getByText('不确定性')).toBeInTheDocument()
    expect(screen.getByText('0.180')).toBeInTheDocument()
    expect(screen.getByText('焦虑压力')).toBeInTheDocument()
    expect(screen.getByText('0.070')).toBeInTheDocument()
    expect(screen.getByText('独食调整等待')).toBeInTheDocument()
    expect(screen.getByText('5.20 分钟')).toBeInTheDocument()
    expect(screen.getByText('样本数')).toBeInTheDocument()
    expect(screen.getByText('428')).toBeInTheDocument()

    expect(panel).toHaveTextContent('启发式代理指标')
    expect(panel).toHaveTextContent('同模型内相对比较')
  })

  it('camelCase 与 0 值兼容(0 不被当成空)', () => {
    const summary = {
      waitExperienceProxyMetrics: {
        waitExperienceProxyIndex: 0,
        preProcessWaitShare: 0,
        waitUncertaintyScore: 0,
        anxietyPressureIndex: 0,
        soloAdjustedWaitMinutes: 0,
        sampleCount: 50
      }
    }
    render(<WaitExperienceProxyPanel summary={summary} />)
    expect(screen.getByTestId('wait-experience-proxy-panel')).toBeInTheDocument()
    expect(screen.getAllByText('0.000').length).toBeGreaterThanOrEqual(4)
    expect(screen.getByText('0.00 分钟')).toBeInTheDocument()
    expect(screen.getByText('50')).toBeInTheDocument()
  })
})
