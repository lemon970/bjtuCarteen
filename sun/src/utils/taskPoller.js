function intervalForNextCall(callsCompleted, intervals) {
  let used = 0
  for (const bucket of intervals) {
    if (callsCompleted < used + bucket.count) {
      return bucket.intervalMs
    }
    used += bucket.count
  }
  return intervals[intervals.length - 1].intervalMs
}

function isTerminalStatus(snapshot) {
  if (!snapshot || typeof snapshot.status !== 'string') return false
  const s = snapshot.status
  return s === 'COMPLETED' || s === 'FAILED' || s === 'CANCELLED'
}

export function createTaskPoller({
  taskId,
  fetcher,
  intervals,
  hardTimeoutMs,
  maxConsecutiveErrors,
  onUpdate,
  onTerminal,
  onError
}) {
  let stopped = false
  let consecutiveErrors = 0
  let pollTimer = null
  let timeoutTimer = null
  let callsCompleted = 0
  const errorBuffer = []

  function clearTimers() {
    if (pollTimer != null) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
    if (timeoutTimer != null) {
      clearTimeout(timeoutTimer)
      timeoutTimer = null
    }
  }

  async function tick() {
    if (stopped) return
    try {
      const snapshot = await fetcher(taskId)
      if (stopped) return
      consecutiveErrors = 0
      errorBuffer.length = 0
      callsCompleted++
      onUpdate(snapshot)
      if (isTerminalStatus(snapshot)) {
        stopped = true
        clearTimers()
        onTerminal(snapshot)
        return
      }
    } catch (err) {
      if (stopped) return
      consecutiveErrors++
      errorBuffer.push(err)
      callsCompleted++
      if (consecutiveErrors >= maxConsecutiveErrors) {
        stopped = true
        clearTimers()
        onError({ reason: 'errors', errors: [...errorBuffer] })
        return
      }
    }
    if (stopped) return
    pollTimer = setTimeout(tick, intervalForNextCall(callsCompleted, intervals))
  }

  return {
    start() {
      if (stopped) return
      timeoutTimer = setTimeout(() => {
        if (stopped) return
        stopped = true
        clearTimers()
        onError({ reason: 'timeout' })
      }, hardTimeoutMs)
      pollTimer = setTimeout(tick, 0)
    },
    stop() {
      stopped = true
      clearTimers()
    }
  }
}
