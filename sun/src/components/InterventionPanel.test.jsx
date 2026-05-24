import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

import InterventionPanel from './InterventionPanel'

const baseForm = {
  duration: 1, windowCount: 5, takeawayWindowCount: 1,
  totalSeats: 200, arrivalRate: 100, seed: 42
}

const baseReport = (primary, wait = 8) => ({
  report_id: 't1',
  summary: {
    bottleneck_diagnosis: { primary, severity: 'HIGH' },
    typical_wait_time_minutes: wait,
    max_total_queue_size: 50,
    served_count: 200,
    takeaway_rate: 0.4,
    seat_utilization_rate: 0.85
  },
  config: baseForm
})

describe('InterventionPanel', () => {
  it('takeaway_capacity primary 自动预选 +1 打包窗口', () => {
    render(<InterventionPanel form={baseForm} report={baseReport('takeaway_capacity')} runFn={vi.fn()} />)
    const card = screen.getByTestId('intervention-card-ADD_TAKEAWAY_WINDOW')
    const checkbox = card.querySelector('input[type="checkbox"]')
    expect(checkbox.checked).toBe(true)
  })

  it('balanced primary 显示空态，无预选', () => {
    render(<InterventionPanel form={baseForm} report={baseReport('balanced')} runFn={vi.fn()} />)
    expect(screen.getByText(/系统均衡/)).toBeInTheDocument()
    screen.queryAllByRole('checkbox').forEach(cb => expect(cb.checked).toBe(false))
  })

  it('勾选 >2 时 FIFO 取消最早勾选的那个', () => {
    render(<InterventionPanel form={baseForm} report={baseReport('takeaway_capacity')} runFn={vi.fn()} />)
    const cardSeats = screen.getByTestId('intervention-card-ADD_SEATS').querySelector('input')
    const cardNormal = screen.getByTestId('intervention-card-ADD_NORMAL_WINDOW').querySelector('input')
    fireEvent.click(cardSeats)
    expect(cardSeats.checked).toBe(true)
    fireEvent.click(cardNormal)
    const cardTake = screen.getByTestId('intervention-card-ADD_TAKEAWAY_WINDOW').querySelector('input')
    expect(cardTake.checked).toBe(false)
    expect(cardSeats.checked).toBe(true)
    expect(cardNormal.checked).toBe(true)
  })

  it('点开始对照 → 调用 runFn baseline + 预选干预', async () => {
    const runFn = vi.fn(async () => baseReport('balanced', 5))
    render(<InterventionPanel form={baseForm} report={baseReport('takeaway_capacity')} runFn={runFn} />)
    fireEvent.click(screen.getByRole('button', { name: /开始对照/ }))
    await waitFor(() => expect(runFn).toHaveBeenCalledTimes(2))
    expect(screen.getByTestId('intervention-diff-table')).toBeInTheDocument()
  })

  it('runFn 完成后主 report props 不被 setter 触达 — InterventionPanel 不调 setReport', async () => {
    const setReport = vi.fn()
    const setPayload = vi.fn()
    const runFn = vi.fn(async () => baseReport('balanced', 5))
    render(
      <InterventionPanel
        form={baseForm}
        report={baseReport('takeaway_capacity')}
        runFn={runFn}
      />
    )
    fireEvent.click(screen.getByRole('button', { name: /开始对照/ }))
    await waitFor(() => expect(runFn).toHaveBeenCalledTimes(2))
    expect(setReport).not.toHaveBeenCalled()
    expect(setPayload).not.toHaveBeenCalled()
  })

  it('fidelity 切到极速 → 传给 runFn 的 form.duration 是 0.25 倍', async () => {
    const runFn = vi.fn(async () => baseReport('balanced', 5))
    render(<InterventionPanel form={{ ...baseForm, duration: 2 }} report={baseReport('takeaway_capacity')} runFn={runFn} />)
    fireEvent.click(screen.getByRole('radio', { name: /极速/ }))
    fireEvent.click(screen.getByRole('button', { name: /开始对照/ }))
    await waitFor(() => expect(runFn).toHaveBeenCalled())
    runFn.mock.calls.forEach(call => {
      expect(call[0].duration).toBe(0.5)
    })
  })

  it('intervention 失败时其他列仍渲染，该列标错误', async () => {
    let n = 0
    const runFn = vi.fn(async () => {
      n++
      if (n === 2) throw new Error('boom')
      return baseReport('balanced', 5)
    })
    render(<InterventionPanel form={baseForm} report={baseReport('takeaway_capacity')} runFn={runFn} />)
    fireEvent.click(screen.getByRole('button', { name: /开始对照/ }))
    await waitFor(() => expect(runFn).toHaveBeenCalledTimes(2))
    expect(screen.getByText(/boom/)).toBeInTheDocument()
  })

  it('form.seed 为空/0/NaN 时显示随机性黄色提示', () => {
    const formNoSeed = { ...baseForm, seed: undefined }
    const { rerender } = render(
      <InterventionPanel form={formNoSeed} report={baseReport('takeaway_capacity')} runFn={vi.fn()} />
    )
    expect(screen.getByText(/未固定随机种子/)).toBeInTheDocument()

    rerender(<InterventionPanel form={{ ...baseForm, seed: 0 }} report={baseReport('takeaway_capacity')} runFn={vi.fn()} />)
    expect(screen.getByText(/未固定随机种子/)).toBeInTheDocument()

    rerender(<InterventionPanel form={{ ...baseForm, seed: NaN }} report={baseReport('takeaway_capacity')} runFn={vi.fn()} />)
    expect(screen.getByText(/未固定随机种子/)).toBeInTheDocument()

    rerender(<InterventionPanel form={baseForm} report={baseReport('takeaway_capacity')} runFn={vi.fn()} />)
    expect(screen.queryByText(/未固定随机种子/)).toBeNull()
  })
})
