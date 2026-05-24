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

  it('BALANCED 路径:渲染绿色 banner 与无瓶颈说明', () => {
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
    expect(panel).toHaveTextContent('无明显瓶颈')
    expect(panel).toHaveTextContent('利用率均 < 0.85')
    expect(screen.queryByTestId('bottleneck-evidence-table')).toBeNull()
  })

  it('单触发:primary=seat_capacity HIGH,渲染卡片 + evidence 行', () => {
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
    expect(primary).toHaveTextContent('座位容量')
    expect(primary).toHaveTextContent('HIGH')

    expect(screen.queryByTestId('bottleneck-card-secondary')).toBeNull()

    const table = screen.getByTestId('bottleneck-evidence-table')
    expect(within(table).getByText('seatUtilizationRate')).toBeInTheDocument()
    expect(within(table).getByText('0.970')).toBeInTheDocument()
    expect(within(table).getByText('0.850')).toBeInTheDocument()
  })

  it('双触发:primary=seat_capacity HIGH,secondary=window_service_capacity LOW;evidence 表 2 行', () => {
    const summary = {
      bottleneck_diagnosis: {
        primary: 'seat_capacity',
        secondary: 'window_service_capacity',
        bottlenecks: [
          {
            type: 'seat_capacity',
            severity: 'high',
            evidence: makeEvidence({ metricName: 'seatUtilizationRate', observedValue: 0.97 })
          },
          {
            type: 'window_service_capacity',
            severity: 'low',
            evidence: makeEvidence({ metricName: 'windowUtilizationMax', observedValue: 0.86, windowId: 2 })
          }
        ]
      }
    }
    render(<BottleneckDiagnosisPanel summary={summary} />)

    const primary = screen.getByTestId('bottleneck-card-primary')
    expect(primary).toHaveAttribute('data-severity', 'high')
    expect(primary).toHaveTextContent('座位容量')

    const secondary = screen.getByTestId('bottleneck-card-secondary')
    expect(secondary).toHaveAttribute('data-severity', 'low')
    expect(secondary).toHaveTextContent('窗口服务能力')

    const rows = screen.getAllByTestId(/^bottleneck-evidence-row-/)
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('座位容量')
    expect(rows[0]).toHaveTextContent('—') // windowId=-1
    expect(rows[1]).toHaveTextContent('窗口服务能力')
    expect(rows[1]).toHaveTextContent('窗口 ID #2')
  })

  it('windowId 边界:-1 显示 "—",0 显示 "窗口 ID #0"(不擅自 +1),7 显示 "窗口 ID #7"', () => {
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

  it('enum 大小写鲁棒:primary "BALANCED" 与 "balanced" 渲染等价;severity "HIGH" 与 "high" 等价', () => {
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
    expect(card).toHaveTextContent('座位容量')
  })
})
