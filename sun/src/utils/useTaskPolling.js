import { useEffect, useRef, useState } from 'react'

import { createTaskPoller } from './taskPoller'

export function useTaskPolling({
  taskId,
  fetcher,
  intervals,
  hardTimeoutMs,
  maxConsecutiveErrors,
  onTerminal,
  onError
}) {
  const [snapshot, setSnapshot] = useState(null)
  const [error, setError] = useState(null)
  const [isPolling, setIsPolling] = useState(false)
  const onTerminalRef = useRef(onTerminal)
  const onErrorRef = useRef(onError)

  useEffect(() => {
    onTerminalRef.current = onTerminal
    onErrorRef.current = onError
  }, [onTerminal, onError])

  useEffect(() => {
    if (!taskId) {
      setSnapshot(null)
      setError(null)
      setIsPolling(false)
      return undefined
    }
    setSnapshot(null)
    setError(null)
    setIsPolling(true)

    const poller = createTaskPoller({
      taskId,
      fetcher,
      intervals,
      hardTimeoutMs,
      maxConsecutiveErrors,
      onUpdate: (s) => setSnapshot(s),
      onTerminal: (s) => {
        setIsPolling(false)
        if (typeof onTerminalRef.current === 'function') onTerminalRef.current(s)
      },
      onError: (e) => {
        setError(e)
        setIsPolling(false)
        if (typeof onErrorRef.current === 'function') onErrorRef.current(e)
      }
    })
    poller.start()
    return () => poller.stop()
  }, [taskId, fetcher, intervals, hardTimeoutMs, maxConsecutiveErrors])

  return { snapshot, error, isPolling }
}
