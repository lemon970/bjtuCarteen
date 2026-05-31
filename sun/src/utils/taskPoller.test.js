import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

import { createTaskPoller } from './taskPoller'

function makeFetcher(...snapshots) {
  const fn = vi.fn()
  snapshots.forEach((s) => {
    if (s instanceof Error) fn.mockRejectedValueOnce(s)
    else fn.mockResolvedValueOnce(s)
  })
  return fn
}

const FAST_INTERVALS = [{ count: Infinity, intervalMs: 1000 }]

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

describe('createTaskPoller', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('start() 立即触发首次 fetch,COMPLETED 后调用 onTerminal 一次并停止 polling', async () => {
    const fetcher = makeFetcher(
      buildSnapshot({ status: 'PENDING' }),
      buildSnapshot({ status: 'RUNNING', started_at_epoch_millis: 2 }),
      buildSnapshot({
        status: 'COMPLETED',
        report_available: true,
        completed_at_epoch_millis: 3
      })
    )
    const updates = []
    const terminals = []
    const errors = []

    const poller = createTaskPoller({
      taskId: 't-1',
      fetcher,
      intervals: FAST_INTERVALS,
      hardTimeoutMs: 60_000,
      maxConsecutiveErrors: 3,
      onUpdate: (s) => updates.push(s),
      onTerminal: (s) => terminals.push(s),
      onError: (e) => errors.push(e)
    })
    poller.start()

    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(1000)
    await vi.advanceTimersByTimeAsync(1000)
    await vi.advanceTimersByTimeAsync(2000)

    expect(fetcher).toHaveBeenCalledTimes(3)
    expect(updates.map((s) => s.status)).toEqual(['PENDING', 'RUNNING', 'COMPLETED'])
    expect(terminals).toHaveLength(1)
    expect(terminals[0].status).toBe('COMPLETED')
    expect(errors).toEqual([])
  })

  it('FAILED 也触发 onTerminal 并停止', async () => {
    const fetcher = makeFetcher(
      buildSnapshot({ status: 'RUNNING' }),
      buildSnapshot({
        status: 'FAILED',
        error_message: 'boom',
        completed_at_epoch_millis: 5
      })
    )
    const terminals = []
    const errors = []
    const poller = createTaskPoller({
      taskId: 't-1',
      fetcher,
      intervals: FAST_INTERVALS,
      hardTimeoutMs: 60_000,
      maxConsecutiveErrors: 3,
      onUpdate: () => {},
      onTerminal: (s) => terminals.push(s),
      onError: (e) => errors.push(e)
    })
    poller.start()

    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(1000)
    await vi.advanceTimersByTimeAsync(2000)

    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(terminals).toHaveLength(1)
    expect(terminals[0].status).toBe('FAILED')
    expect(errors).toEqual([])
  })

  it('连续 3 次 fetch 失败 → onError(reason=errors) 并停止', async () => {
    const fetcher = makeFetcher(new Error('net1'), new Error('net2'), new Error('net3'))
    const errors = []
    const terminals = []
    const poller = createTaskPoller({
      taskId: 't-1',
      fetcher,
      intervals: FAST_INTERVALS,
      hardTimeoutMs: 60_000,
      maxConsecutiveErrors: 3,
      onUpdate: () => {},
      onTerminal: (s) => terminals.push(s),
      onError: (e) => errors.push(e)
    })
    poller.start()

    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(1000)
    await vi.advanceTimersByTimeAsync(1000)
    await vi.advanceTimersByTimeAsync(2000)

    expect(fetcher).toHaveBeenCalledTimes(3)
    expect(errors).toHaveLength(1)
    expect(errors[0].reason).toBe('errors')
    expect(terminals).toEqual([])
  })

  it('成功打断错误连击,counter 重置', async () => {
    const fetcher = makeFetcher(
      new Error('net1'),
      new Error('net2'),
      buildSnapshot({ status: 'RUNNING' }),
      new Error('net3'),
      new Error('net4'),
      buildSnapshot({ status: 'COMPLETED', report_available: true })
    )
    const errors = []
    const terminals = []
    const poller = createTaskPoller({
      taskId: 't-1',
      fetcher,
      intervals: FAST_INTERVALS,
      hardTimeoutMs: 60_000,
      maxConsecutiveErrors: 3,
      onUpdate: () => {},
      onTerminal: (s) => terminals.push(s),
      onError: (e) => errors.push(e)
    })
    poller.start()
    for (let i = 0; i < 8; i++) {
      await vi.advanceTimersByTimeAsync(1000)
    }

    expect(fetcher).toHaveBeenCalledTimes(6)
    expect(errors).toEqual([])
    expect(terminals).toHaveLength(1)
    expect(terminals[0].status).toBe('COMPLETED')
  })

  it('hard timeout 触发 onError(reason=timeout) 并停止', async () => {
    const fetcher = vi.fn().mockResolvedValue(buildSnapshot({ status: 'RUNNING' }))
    const errors = []
    const terminals = []
    const poller = createTaskPoller({
      taskId: 't-1',
      fetcher,
      intervals: FAST_INTERVALS,
      hardTimeoutMs: 5000,
      maxConsecutiveErrors: 3,
      onUpdate: () => {},
      onTerminal: (s) => terminals.push(s),
      onError: (e) => errors.push(e)
    })
    poller.start()

    await vi.advanceTimersByTimeAsync(7000)

    expect(errors).toHaveLength(1)
    expect(errors[0].reason).toBe('timeout')
    expect(terminals).toEqual([])
    const callsAtTimeout = fetcher.mock.calls.length
    await vi.advanceTimersByTimeAsync(5000)
    expect(fetcher).toHaveBeenCalledTimes(callsAtTimeout)
  })

  it('stop() 阻止后续 fetch', async () => {
    const fetcher = vi.fn().mockResolvedValue(buildSnapshot({ status: 'RUNNING' }))
    const terminals = []
    const errors = []
    const poller = createTaskPoller({
      taskId: 't-1',
      fetcher,
      intervals: FAST_INTERVALS,
      hardTimeoutMs: 60_000,
      maxConsecutiveErrors: 3,
      onUpdate: () => {},
      onTerminal: (s) => terminals.push(s),
      onError: (e) => errors.push(e)
    })
    poller.start()
    await vi.advanceTimersByTimeAsync(0)
    expect(fetcher).toHaveBeenCalledTimes(1)
    poller.stop()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(terminals).toEqual([])
    expect(errors).toEqual([])
  })

  it('terminal 后再调 stop() 无副作用', async () => {
    const fetcher = makeFetcher(
      buildSnapshot({ status: 'COMPLETED', report_available: true })
    )
    const terminals = []
    const poller = createTaskPoller({
      taskId: 't-1',
      fetcher,
      intervals: FAST_INTERVALS,
      hardTimeoutMs: 60_000,
      maxConsecutiveErrors: 3,
      onUpdate: () => {},
      onTerminal: (s) => terminals.push(s),
      onError: () => {}
    })
    poller.start()
    await vi.advanceTimersByTimeAsync(0)
    expect(terminals).toHaveLength(1)
    expect(() => poller.stop()).not.toThrow()
  })
})
