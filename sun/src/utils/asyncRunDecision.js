import { ASYNC_RUN_THRESHOLDS } from '../constants'
import { toNumber } from './simulation'

export function decideRunMode(form, userToggle) {
  if (userToggle === 'sync' || userToggle === 'async') {
    return userToggle
  }
  const duration = Math.max(0, toNumber(form?.duration, 0))
  const arrivalRate = Math.max(0, toNumber(form?.arrivalRate, 0))
  const totalStudents = Math.max(0, Math.floor(toNumber(form?.totalStudents, 0)))
  const raw = duration * arrivalRate
  const cap = totalStudents > 0 ? totalStudents : raw
  const estimatedArrivals = Math.min(raw, cap)
  if (estimatedArrivals >= ASYNC_RUN_THRESHOLDS.estimatedArrivals) {
    return 'async'
  }
  if (duration >= ASYNC_RUN_THRESHOLDS.durationHours) {
    return 'async'
  }
  return 'sync'
}
