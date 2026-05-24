import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = globalThis.ResizeObserver || ResizeObserverStub

vi.mock('./utils/useEcharts', () => ({
  useEcharts: () => ({ current: null })
}))

vi.mock('./api/simulationApi', () => ({
  runSimulation: vi.fn(),
  runSimulationAsync: vi.fn(),
  getTaskStatus: vi.fn(),
  getReportById: vi.fn(),
  loadLatestReport: vi.fn(() => new Promise(() => {})),
  loadScenarioCatalog: vi.fn(() => Promise.resolve({ scenarios: [] })),
  runScenarioBatch: vi.fn(),
  csvExportUrl: vi.fn(() => '#'),
  runAnalysis: vi.fn()
}))

import App from './App'
import * as api from './api/simulationApi'

const PENDING = {
  task_id: 'task-1',
  report_id: 'report-1',
  status: 'PENDING',
  report_available: false,
  submitted_at_epoch_millis: 1,
  started_at_epoch_millis: 0,
  completed_at_epoch_millis: 0,
  error_message: ''
}
const RUNNING = { ...PENDING, status: 'RUNNING', started_at_epoch_millis: 2 }
const COMPLETED = {
  ...PENDING,
  status: 'COMPLETED',
  report_available: true,
  started_at_epoch_millis: 2,
  completed_at_epoch_millis: 9
}

describe('App 异步 happy path', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    window.location.hash = ''
    api.runSimulation.mockReset()
    api.runSimulationAsync.mockReset()
    api.getTaskStatus.mockReset()
    api.getReportById.mockReset()
    api.loadScenarioCatalog.mockReset().mockResolvedValue({ scenarios: [] })
    api.loadLatestReport.mockReset().mockImplementation(() => new Promise(() => {}))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('runMode=async → 提交 → polling COMPLETED → getReportById → DisplayPage', async () => {
    api.runSimulationAsync.mockResolvedValue(PENDING)
    api.getTaskStatus
      .mockResolvedValueOnce(RUNNING)
      .mockResolvedValueOnce(COMPLETED)
    api.getReportById.mockResolvedValue({
      report_id: 'report-1',
      config: {},
      summary: { timeline: [] }
    })

    render(<App />)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })

    fireEvent.change(screen.getByTestId('run-mode-select'), {
      target: { value: 'async' }
    })

    const form = document.getElementById('single-run-form')
    expect(form).not.toBeNull()
    await act(async () => {
      fireEvent.submit(form)
    })

    // runSimulationAsync resolve → setActiveTaskId('task-1')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    // 首次 tick:getTaskStatus → RUNNING
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    // 下一次 tick(1s 间隔):getTaskStatus → COMPLETED
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    // getReportById resolve → setReport → navigate('display')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })

    expect(api.runSimulation).not.toHaveBeenCalled()
    expect(api.runSimulationAsync).toHaveBeenCalledTimes(1)
    expect(api.getTaskStatus).toHaveBeenCalled()
    api.getTaskStatus.mock.calls.forEach(([id]) => expect(id).toBe('task-1'))
    expect(api.getReportById).toHaveBeenCalledWith('report-1')

    // 已离开 InputPage(其 h1 "信息输入" 不再出现)
    expect(
      screen.queryByRole('heading', { level: 1, name: '信息输入' })
    ).toBeNull()
    // DisplayPage 已渲染:其 MetricCard 含 "到达人数"
    expect(screen.getAllByText('到达人数').length).toBeGreaterThan(0)
  })
})
