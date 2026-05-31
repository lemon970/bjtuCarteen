import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'

import { useTaskPolling } from './useTaskPolling'

function buildSnapshot(overrides = {}) {
  return {
    task_id: 't-1',
    report_id: 'r-1',
    status: 'PENDING',
    report_available: false,
    submitted_at_epoch_millis: 1,
    started_at_epoch_millis: 0,
    completed_at_epoch_millis: 0,
    error_message: '',
    ...overrides
  }
}

const FAST = {
  intervals: [{ count: Infinity, intervalMs: 1000 }],
  hardTimeoutMs: 60_000,
  maxConsecutiveErrors: 3
}

describe('useTaskPolling', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('taskId=null 不触发 fetch,返回 idle 状态', async () => {
    const fetcher = vi.fn()
    const { result } = renderHook(() =>
      useTaskPolling({ taskId: null, fetcher, onTerminal: () => {}, onError: () => {}, ...FAST })
    )
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
    })
    expect(fetcher).not.toHaveBeenCalled()
    expect(result.current.snapshot).toBeNull()
    expect(result.current.error).toBeNull()
    expect(result.current.isPolling).toBe(false)
  })

  it('taskId 提供后触发 polling,COMPLETED 时 onTerminal 被调,snapshot 暴露', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(buildSnapshot({ status: 'RUNNING' }))
      .mockResolvedValueOnce(
        buildSnapshot({ status: 'COMPLETED', report_available: true, completed_at_epoch_millis: 9 })
      )
    const onTerminal = vi.fn()
    const { result } = renderHook(() =>
      useTaskPolling({ taskId: 't-1', fetcher, onTerminal, onError: () => {}, ...FAST })
    )
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect(onTerminal).toHaveBeenCalledTimes(1)
    expect(onTerminal.mock.calls[0][0].status).toBe('COMPLETED')
    expect(result.current.snapshot.status).toBe('COMPLETED')
    expect(result.current.isPolling).toBe(false)
  })

  it('unmount 后不再触发 fetch', async () => {
    const fetcher = vi.fn().mockResolvedValue(buildSnapshot({ status: 'RUNNING' }))
    const { unmount } = renderHook(() =>
      useTaskPolling({ taskId: 't-1', fetcher, onTerminal: () => {}, onError: () => {}, ...FAST })
    )
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(fetcher).toHaveBeenCalledTimes(1)
    unmount()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000)
    })
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('taskId 切换时停掉旧 poller 起新 poller', async () => {
    const fetcher = vi
      .fn()
      .mockImplementation((id) => Promise.resolve(buildSnapshot({ task_id: id, status: 'RUNNING' })))
    const { rerender } = renderHook(
      ({ taskId }) =>
        useTaskPolling({ taskId, fetcher, onTerminal: () => {}, onError: () => {}, ...FAST }),
      { initialProps: { taskId: 't-1' } }
    )
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(fetcher).toHaveBeenLastCalledWith('t-1')
    rerender({ taskId: 't-2' })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(fetcher).toHaveBeenLastCalledWith('t-2')
  })
})
