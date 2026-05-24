import { describe, it, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'

import BottleneckDiagnosisPanel from './BottleneckDiagnosisPanel'

function makeEvidence({ metricName, observedValue = 0.97, threshold = 0.85, windowId = -1 }) {
  return {
    metric_name: metricName,
    observed_value: observedValue,
    threshold,
    window_id: windowId
  }
}

describe('BottleneckDiagnosisPanel', () => {
  it('null 防御:summary.bottleneck_diagnosis 为 null 时整 panel 不渲染', () => {
    const { container } = render(
      <BottleneckDiagnosisPanel summary={{ bottleneck_diagnosis: null }} />
    )
    expect(container.firstChild).toBeNull()
  })

  it('BALANCED 路径:展示均衡结论,不出现建议、不出现折叠区', () => {
    const summary = {
      bottleneck_diagnosis: {
        primary: 'balanced',
        secondary: null,
        bottlenecks: []
      }
    }
    render(<BottleneckDiagnosisPanel summary={summary} />)
    const panel = screen.getByTestId('bottleneck-diagnosis-panel')
    expect(panel).toHaveAttribute('data-state', 'balanced')
    expect(panel).toHaveTextContent('系统均衡')
    expect(panel).not.toHaveTextContent('可考虑')
    expect(screen.queryByTestId('bottleneck-evidence-details')).toBeNull()
  })

  it('座位瓶颈 HIGH:渲染结论 + 操作建议 + 折叠详情(默认折叠)', () => {
    const summary = {
      bottleneck_diagnosis: {
        primary: 'seat_capacity',
        secondary: null,
        bottlenecks: [{
          type: 'seat_capacity',
          severity: 'high',
          evidence: makeEvidence({ metricName: 'seatUtilizationRate', observedValue: 0.97 })
        }]
      }
    }
    render(<BottleneckDiagnosisPanel summary={summary} />)
    const panel = screen.getByTestId('bottleneck-diagnosis-panel')
    expect(panel).toHaveAttribute('data-state', 'triggered')

    const primary = screen.getByTestId('bottleneck-card-primary')
    expect(primary).toHaveAttribute('data-severity', 'high')
    expect(primary).toHaveTextContent('座位偏紧')
    expect(primary).toHaveTextContent('高峰时段')
    expect(primary).toHaveTextContent('可考虑增加座位')

    expect(screen.queryByTestId('bottleneck-card-secondary')).toBeNull()

    const details = screen.getByTestId('bottleneck-evidence-details')
    // <details> 默认折叠
    expect(details).not.toHaveAttribute('open')
    expect(details).toHaveTextContent('查看诊断证据')
    expect(details).toHaveTextContent('1 项')
  })

  it('窗口繁忙双触发:primary + secondary,折叠 summary 显示 2 项', () => {
    const summary = {
      bottleneck_diagnosis: {
        primary: 'window_service_capacity',
        secondary: 'arrival_surge',
        bottlenecks: [
          {
            type: 'window_service_capacity',
            severity: 'high',
            evidence: makeEvidence({ metricName: 'windowUtilizationMax', observedValue: 0.96, windowId: 2 })
          },
          {
            type: 'arrival_surge',
            severity: 'medium',
            evidence: makeEvidence({ metricName: 'queuePressureMax', observedValue: 0.92, windowId: -1 })
          }
        ]
      }
    }
    render(<BottleneckDiagnosisPanel summary={summary} />)
    const primary = screen.getByTestId('bottleneck-card-primary')
    expect(primary).toHaveTextContent('窗口服务繁忙')
    expect(primary).toHaveTextContent('可考虑增开窗口')

    const secondary = screen.getByTestId('bottleneck-card-secondary')
    expect(secondary).toHaveAttribute('data-severity', 'medium')
    expect(secondary).toHaveTextContent('到达冲击明显')
    expect(secondary).toHaveTextContent('可考虑')

    const details = screen.getByTestId('bottleneck-evidence-details')
    expect(details).toHaveTextContent('2 项')
    // 折叠详情内仍包含完整 evidence 表
    const table = within(details).getByTestId('bottleneck-evidence-table')
    const rows = within(table).getAllByTestId(/^bottleneck-evidence-row-/)
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('窗口服务能力')
    expect(rows[1]).toHaveTextContent('到达冲击')
  })

  it('打包窗口与到达冲击文案命中', () => {
    render(
      <BottleneckDiagnosisPanel
        summary={{
          bottleneck_diagnosis: {
            primary: 'takeaway_capacity',
            secondary: null,
            bottlenecks: [{
              type: 'takeaway_capacity',
              severity: 'low',
              evidence: makeEvidence({ metricName: 'takeawayWindowUtilizationMax', windowId: 3 })
            }]
          }
        }}
      />
    )
    const primary = screen.getByTestId('bottleneck-card-primary')
    expect(primary).toHaveTextContent('打包窗口繁忙')
    expect(primary).toHaveTextContent('增加打包窗口')
  })

  it('windowId 边界(展开后):-1 显示 "—",0 显示 "窗口 ID #0"(不擅自 +1),7 显示 "窗口 ID #7"', () => {
    const summary = {
      bottleneck_diagnosis: {
        primary: 'window_service_capacity',
        secondary: null,
        bottlenecks: [
          {
            type: 'window_service_capacity',
            severity: 'high',
            evidence: makeEvidence({ metricName: 'windowUtilizationMax', windowId: 0 })
          },
          {
            type: 'takeaway_capacity',
            severity: 'medium',
            evidence: makeEvidence({ metricName: 'takeawayWindowUtilizationMax', windowId: 7 })
          },
          {
            type: 'arrival_surge',
            severity: 'low',
            evidence: makeEvidence({ metricName: 'queuePressureMax', windowId: -1 })
          }
        ]
      }
    }
    render(<BottleneckDiagnosisPanel summary={summary} />)
    const rows = screen.getAllByTestId(/^bottleneck-evidence-row-/)
    expect(rows[0]).toHaveTextContent('窗口 ID #0')
    expect(rows[0]).not.toHaveTextContent('窗口 ID #1')
    expect(rows[1]).toHaveTextContent('窗口 ID #7')
    expect(rows[2]).toHaveTextContent('—')
  })

  it('enum 大小写鲁棒:primary "BALANCED" 与 "balanced"、severity "HIGH" 与 "high" 等价', () => {
    const upper = render(
      <BottleneckDiagnosisPanel
        summary={{ bottleneck_diagnosis: { primary: 'BALANCED', secondary: null, bottlenecks: [] } }}
      />
    )
    expect(upper.container.querySelector('[data-testid="bottleneck-diagnosis-panel"]'))
      .toHaveAttribute('data-state', 'balanced')
    upper.unmount()

    const triggered = render(
      <BottleneckDiagnosisPanel
        summary={{
          bottleneck_diagnosis: {
            primary: 'SEAT_CAPACITY',
            secondary: null,
            bottlenecks: [{
              type: 'SEAT_CAPACITY',
              severity: 'HIGH',
              evidence: makeEvidence({ metricName: 'seatUtilizationRate' })
            }]
          }
        }}
      />
    )
    const card = triggered.container.querySelector('[data-testid="bottleneck-card-primary"]')
    expect(card).toHaveAttribute('data-severity', 'high')
    expect(card).toHaveTextContent('座位偏紧')
  })
})
