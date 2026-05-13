import { DEFAULT_FORM, MAX_RENDERED_SEATS, MAX_SEATS, MAX_STUDENTS } from '../constants'

// [重构] 前后端字段存在 camelCase/snake_case 两套命名，统一在工具层读取，页面组件只处理展示。
export function read(obj, ...keys) {
  for (const key of keys) {
    if (obj && obj[key] !== undefined && obj[key] !== null) {
      return obj[key]
    }
  }
  return undefined
}

export function toNumber(value, fallback = 0) {
  const n = Number(value)
  return Number.isFinite(n) ? n : fallback
}

export function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value))
}

export function formatNumber(value, digits = 2) {
  const n = Number(value)
  return Number.isFinite(n) ? n.toFixed(digits) : '0.00'
}

export function formatPercent(value, digits = 1) {
  return `${formatNumber(toNumber(value, 0) * 100, digits)}%`
}

export function formatClock(seconds) {
  const safeSeconds = Math.max(0, Math.floor(toNumber(seconds, 0)))
  const minutes = Math.floor(safeSeconds / 60)
  const remainder = safeSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`
}

export function buildSeatCells(point, fallbackCells = []) {
  const totalSeats = Math.max(0, Math.floor(toNumber(read(point, 'total_seats', 'totalSeats'), 0)))
  const occupiedSeats = Math.max(0, Math.floor(toNumber(read(point, 'occupied_seats', 'occupiedSeats', 'dining_student_count', 'diningStudentCount'), 0)))
  const sourceCells = read(point, 'seat_cells', 'seatCells')
  if (Array.isArray(sourceCells) && sourceCells.length > 0) {
    return compactSeatCells(sourceCells, totalSeats || sourceCells.length, occupiedSeats)
  }
  if (Array.isArray(fallbackCells) && fallbackCells.length > 0) {
    const occupiedLimit = Math.max(0, Math.floor(toNumber(occupiedSeats, 0)))
    const projected = fallbackCells.map((cell, index) => ({
      ...cell,
      status: index < occupiedLimit ? 'OCCUPIED' : read(cell, 'status') || 'FREE',
      occupied: index < occupiedLimit
    }))
    return compactSeatCells(projected, totalSeats || fallbackCells.length, occupiedLimit)
  }

  return compactSeatCells(Array.from({ length: totalSeats }, (_, index) => ({
    seat_id: index,
    table_id: Math.floor(index / 4),
    row: Math.floor(index / 12),
    column: index % 12,
    area: ['A', 'B', 'C'][Math.floor(index / 36) % 3],
    status: index < occupiedSeats ? 'OCCUPIED' : 'FREE',
    occupied: index < occupiedSeats
  })), totalSeats, occupiedSeats)
}

function compactSeatCells(cells, totalSeats, occupiedSeats) {
  const source = Array.isArray(cells) ? cells : []
  const safeTotal = Math.max(source.length, Math.floor(toNumber(totalSeats, source.length)))
  if (source.length <= MAX_RENDERED_SEATS) {
    return source
  }

  // [重构] 大座位图按比例抽样渲染，原因是 10^3 量级座位不能直接生成上千个 DOM 节点影响回放流畅度。
  const renderedCount = Math.min(MAX_RENDERED_SEATS, source.length)
  const occupiedRatio = safeTotal === 0 ? 0 : clamp(toNumber(occupiedSeats, 0) / safeTotal, 0, 1)
  const renderedOccupied = Math.round(renderedCount * occupiedRatio)
  const step = source.length / renderedCount

  return Array.from({ length: renderedCount }, (_, index) => {
    const sampled = source[Math.min(source.length - 1, Math.floor(index * step))] || {}
    return {
      ...sampled,
      seat_id: read(sampled, 'seat_id', 'seatId') ?? index,
      row: Math.floor(index / 20),
      column: index % 20,
      area: read(sampled, 'area') || ['A', 'B', 'C', 'D'][Math.floor(index / 90) % 4],
      status: index < renderedOccupied ? 'OCCUPIED' : 'FREE',
      occupied: index < renderedOccupied,
      sampled: true,
      total_seats: safeTotal
    }
  })
}

export function summarizeSeatAreas(cells) {
  const areaMap = new Map()
  for (const cell of Array.isArray(cells) ? cells : []) {
    const area = read(cell, 'area') || 'A'
    const current = areaMap.get(area) || { area, total: 0, occupied: 0, cleaning: 0 }
    current.total += 1
    const status = String(read(cell, 'status') || '').toUpperCase()
    if (status === 'OCCUPIED' || read(cell, 'occupied') === true) {
      current.occupied += 1
    }
    if (status === 'CLEANING') {
      current.cleaning += 1
    }
    areaMap.set(area, current)
  }
  return [...areaMap.values()].map((item) => ({
    ...item,
    utilization: item.total === 0 ? 0 : item.occupied / item.total
  }))
}

export function buildPayload(form) {
  const windowCount = Math.max(1, Math.floor(toNumber(form.windowCount, 1)))
  const arrivalRate = Math.max(0, toNumber(form.arrivalRate, DEFAULT_FORM.arrivalRate))
  const takeawayWindowCount = Math.min(
    windowCount,
    Math.max(0, Math.floor(toNumber(form.takeawayWindowCount, 0)))
  )
  const serviceMin = Math.max(1, Math.floor(toNumber(form.serviceMin, 60)))
  const serviceMax = Math.max(1, Math.floor(toNumber(form.serviceMax, 180)))
  const diningMin = Math.max(1, Math.floor(toNumber(form.diningMin, 600)))
  const diningMax = Math.max(1, Math.floor(toNumber(form.diningMax, 1200)))
  const preferenceMin = clamp(toNumber(form.preferenceMin, 0.1), 0, 1)
  const preferenceMax = clamp(toNumber(form.preferenceMax, 0.3), 0, 1)

  // [重构] 请求字段统一改为 snake_case，原因是后端响应与测试契约以 snake_case 为主。
  const payload = {
    simulation_name: String(form.simulationName || DEFAULT_FORM.simulationName),
    duration: Math.max(0.1, toNumber(form.duration, DEFAULT_FORM.duration)),
    arrival_rate: arrivalRate,
    queue_limit: Math.max(0, Math.floor(toNumber(form.queueLimit, DEFAULT_FORM.queueLimit))),
    pack_probability: clamp(toNumber(form.packProbability, DEFAULT_FORM.packProbability), 0, 1),
    group_arrival_prob: clamp(toNumber(form.groupArrivalProb, DEFAULT_FORM.groupArrivalProb), 0, 1),
    party_size: Math.max(1, Math.floor(toNumber(form.partySize, DEFAULT_FORM.partySize))),
    walk_time_mean: Math.max(0, toNumber(form.walkTimeMean, DEFAULT_FORM.walkTimeMean)),
    congestion_penalty: Math.max(0, toNumber(form.congestionPenalty, DEFAULT_FORM.congestionPenalty)),
    base_config: {
      window_count: windowCount,
      takeaway_window_count: takeawayWindowCount,
      takeaway_service_time_multiplier: Math.max(
        1,
        toNumber(form.takeawayServiceTimeMultiplier, DEFAULT_FORM.takeawayServiceTimeMultiplier)
      ),
      total_seats: Math.min(MAX_SEATS, Math.max(0, Math.floor(toNumber(form.totalSeats, DEFAULT_FORM.totalSeats)))),
      total_students: Math.min(
        MAX_STUDENTS,
        Math.max(0, Math.floor(toNumber(form.totalStudents, DEFAULT_FORM.totalStudents)))
      )
    },
    weather_config: {
      current_weather: form.currentWeather || DEFAULT_FORM.currentWeather,
      weather_impact_factor: Math.max(0, toNumber(form.weatherImpactFactor, DEFAULT_FORM.weatherImpactFactor))
    },
    random_bounds: {
      arrival_interval: Math.max(0, Math.floor(toNumber(form.arrivalInterval, DEFAULT_FORM.arrivalInterval))),
      service_range: [Math.min(serviceMin, serviceMax), Math.max(serviceMin, serviceMax)],
      dining_range: [Math.min(diningMin, diningMax), Math.max(diningMin, diningMax)],
      preference_range: [Math.min(preferenceMin, preferenceMax), Math.max(preferenceMin, preferenceMax)]
    },
    arrival_dist: {
      type: 'POISSON',
      lambda: arrivalRate
    },
    normal_service_dist: {
      type: 'NORMAL',
      mean: Math.max(1, toNumber(form.serviceMean, (serviceMin + serviceMax) / 2)),
      std: Math.max(1, (Math.max(serviceMin, serviceMax) - Math.min(serviceMin, serviceMax)) / 6),
      min: Math.min(serviceMin, serviceMax),
      max: Math.max(serviceMin, serviceMax)
    },
    window_service_dist: {
      type: 'NORMAL',
      mean: Math.max(1, toNumber(form.serviceMean, (serviceMin + serviceMax) / 2)),
      std: Math.max(1, (Math.max(serviceMin, serviceMax) - Math.min(serviceMin, serviceMax)) / 6),
      min: Math.min(serviceMin, serviceMax),
      max: Math.max(serviceMin, serviceMax)
    },
    dining_time_dist: {
      type: 'NORMAL',
      mean: Math.max(1, toNumber(form.diningMean, (diningMin + diningMax) / 2)),
      std: Math.max(1, (Math.max(diningMin, diningMax) - Math.min(diningMin, diningMax)) / 6),
      min: Math.min(diningMin, diningMax),
      max: Math.max(diningMin, diningMax)
    },
    peak_config: {
      class_peak_enabled: Boolean(form.peakEnabled),
      class_peak_start_minute: Math.max(0, Math.floor(toNumber(form.lunchPeakStart, DEFAULT_FORM.lunchPeakStart))),
      class_peak_end_minute: Math.max(0, Math.floor(toNumber(form.lunchPeakEnd, DEFAULT_FORM.lunchPeakEnd))),
      class_peak_multiplier: Math.max(1, toNumber(form.lunchPeakMultiplier, DEFAULT_FORM.lunchPeakMultiplier)),
      class_peak_windows: [
        {
          start_minute: Math.max(0, Math.floor(toNumber(form.lunchPeakStart, DEFAULT_FORM.lunchPeakStart))),
          end_minute: Math.max(0, Math.floor(toNumber(form.lunchPeakEnd, DEFAULT_FORM.lunchPeakEnd))),
          multiplier: Math.max(1, toNumber(form.lunchPeakMultiplier, DEFAULT_FORM.lunchPeakMultiplier))
        },
        {
          start_minute: Math.max(0, Math.floor(toNumber(form.dinnerPeakStart, DEFAULT_FORM.dinnerPeakStart))),
          end_minute: Math.max(0, Math.floor(toNumber(form.dinnerPeakEnd, DEFAULT_FORM.dinnerPeakEnd))),
          multiplier: Math.max(1, toNumber(form.dinnerPeakMultiplier, DEFAULT_FORM.dinnerPeakMultiplier))
        }
      ]
    }
  }

  const seed = toNumber(form.seed, NaN)
  if (Number.isFinite(seed)) {
    payload.seed = Math.trunc(seed)
  }
  return payload
}

export function applyPayloadToForm(payload) {
  const base = read(payload, 'baseConfig', 'base_config') || {}
  const weather = read(payload, 'weatherConfig', 'weather_config') || {}
  const random = read(payload, 'randomBounds', 'random_bounds') || {}
  const arrivalDist = read(payload, 'arrivalDist', 'arrival_dist') || {}
  const normalServiceDist = read(payload, 'normalServiceDist', 'normal_service_dist') || {}
  const diningTimeDist = read(payload, 'diningTimeDist', 'dining_time_dist') || {}
  const peak = read(payload, 'peakConfig', 'peak_config') || {}
  const peakWindows = read(peak, 'classPeakWindows', 'class_peak_windows') || []
  const firstPeak = Array.isArray(peakWindows) && peakWindows.length > 0 ? peakWindows[0] : {}
  const secondPeak = Array.isArray(peakWindows) && peakWindows.length > 1 ? peakWindows[1] : {}
  const serviceRange = read(random, 'serviceRange', 'service_range') || [
    DEFAULT_FORM.serviceMin,
    DEFAULT_FORM.serviceMax
  ]
  const diningRange = read(random, 'diningRange', 'dining_range') || [
    DEFAULT_FORM.diningMin,
    DEFAULT_FORM.diningMax
  ]
  const preferenceRange = read(random, 'preferenceRange', 'preference_range') || [
    DEFAULT_FORM.preferenceMin,
    DEFAULT_FORM.preferenceMax
  ]

  return {
    ...DEFAULT_FORM,
    simulationName: read(payload, 'simulationName', 'simulation_name') || DEFAULT_FORM.simulationName,
    duration: read(payload, 'duration', 'duration_hours', 'duration_hour') ?? DEFAULT_FORM.duration,
    arrivalRate: read(payload, 'arrivalRate', 'arrival_rate', 'arrival_rate_per_hour') ?? DEFAULT_FORM.arrivalRate,
    queueLimit: read(payload, 'queueLimit', 'queue_limit') ?? DEFAULT_FORM.queueLimit,
    packProbability: read(payload, 'packProbability', 'pack_probability') ?? DEFAULT_FORM.packProbability,
    seed: read(payload, 'seed') ?? DEFAULT_FORM.seed,
    groupArrivalProb: read(payload, 'groupArrivalProb', 'group_arrival_prob') ?? DEFAULT_FORM.groupArrivalProb,
    partySize: read(payload, 'partySize', 'party_size') ?? DEFAULT_FORM.partySize,
    walkTimeMean: read(payload, 'walkTimeMean', 'walk_time_mean') ?? DEFAULT_FORM.walkTimeMean,
    congestionPenalty: read(payload, 'congestionPenalty', 'congestion_penalty') ?? DEFAULT_FORM.congestionPenalty,
    windowCount: read(base, 'windowCount', 'window_count') ?? DEFAULT_FORM.windowCount,
    takeawayWindowCount: read(base, 'takeawayWindowCount', 'takeaway_window_count') ?? DEFAULT_FORM.takeawayWindowCount,
    takeawayServiceTimeMultiplier:
      read(base, 'takeawayServiceTimeMultiplier', 'takeaway_service_time_multiplier') ??
      DEFAULT_FORM.takeawayServiceTimeMultiplier,
    totalSeats: read(base, 'totalSeats', 'total_seats') ?? DEFAULT_FORM.totalSeats,
    totalStudents: read(base, 'totalStudents', 'total_students') ?? DEFAULT_FORM.totalStudents,
    arrivalLambda: read(payload, 'arrivalRate', 'arrival_rate') ?? read(arrivalDist, 'lambda', 'rate') ?? DEFAULT_FORM.arrivalLambda,
    serviceMean: read(normalServiceDist, 'mean', 'mu') ?? DEFAULT_FORM.serviceMean,
    diningMean: read(diningTimeDist, 'mean', 'mu') ?? DEFAULT_FORM.diningMean,
    peakEnabled: read(peak, 'classPeakEnabled', 'class_peak_enabled') ?? DEFAULT_FORM.peakEnabled,
    lunchPeakStart:
      read(firstPeak, 'startMinute', 'start_minute') ??
      read(peak, 'classPeakStartMinute', 'class_peak_start_minute') ??
      DEFAULT_FORM.lunchPeakStart,
    lunchPeakEnd:
      read(firstPeak, 'endMinute', 'end_minute') ??
      read(peak, 'classPeakEndMinute', 'class_peak_end_minute') ??
      DEFAULT_FORM.lunchPeakEnd,
    lunchPeakMultiplier:
      read(firstPeak, 'multiplier') ??
      read(peak, 'classPeakMultiplier', 'class_peak_multiplier') ??
      DEFAULT_FORM.lunchPeakMultiplier,
    dinnerPeakStart: read(secondPeak, 'startMinute', 'start_minute') ?? DEFAULT_FORM.dinnerPeakStart,
    dinnerPeakEnd: read(secondPeak, 'endMinute', 'end_minute') ?? DEFAULT_FORM.dinnerPeakEnd,
    dinnerPeakMultiplier: read(secondPeak, 'multiplier') ?? DEFAULT_FORM.dinnerPeakMultiplier,
    currentWeather: read(weather, 'currentWeather', 'current_weather') ?? DEFAULT_FORM.currentWeather,
    weatherImpactFactor:
      read(weather, 'weatherImpactFactor', 'weather_impact_factor') ?? DEFAULT_FORM.weatherImpactFactor,
    arrivalInterval: read(random, 'arrivalInterval', 'arrival_interval') ?? DEFAULT_FORM.arrivalInterval,
    serviceMin: Array.isArray(serviceRange) ? serviceRange[0] : DEFAULT_FORM.serviceMin,
    serviceMax: Array.isArray(serviceRange) ? serviceRange[1] : DEFAULT_FORM.serviceMax,
    diningMin: Array.isArray(diningRange) ? diningRange[0] : DEFAULT_FORM.diningMin,
    diningMax: Array.isArray(diningRange) ? diningRange[1] : DEFAULT_FORM.diningMax,
    preferenceMin: Array.isArray(preferenceRange) ? preferenceRange[0] : DEFAULT_FORM.preferenceMin,
    preferenceMax: Array.isArray(preferenceRange) ? preferenceRange[1] : DEFAULT_FORM.preferenceMax
  }
}

export function normalizePoint(point, index) {
  const timeSeconds = read(point, 'time_seconds', 'timeSeconds', 'time') ?? index * 60
  return {
    timeSeconds,
    minute: read(point, 'minute') ?? Math.round(timeSeconds / 60),
    queue: read(point, 'total_queue_size', 'totalQueueSize', 'queueing_student_count', 'queueingStudentCount') ?? 0,
    seats: read(point, 'occupied_seats', 'occupiedSeats', 'dining_student_count', 'diningStudentCount') ?? 0,
    seatRate: read(point, 'seat_utilization_rate', 'seatUtilizationRate') ?? 0,
    arrived: read(point, 'cumulative_arrived_count', 'cumulativeArrivedCount', 'arrived_count', 'arrivedCount') ?? 0,
    served: read(point, 'cumulative_served_count', 'cumulativeServedCount', 'served_count', 'servedCount') ?? 0,
    eventMessage: read(point, 'event_message', 'eventMessage') || ''
  }
}

export function buildAnalysis(summary) {
  const timeline = Array.isArray(summary?.timeline) ? summary.timeline.map(normalizePoint) : []
  const peak = timeline.reduce(
    (best, point) => (point.seatRate > best.seatRate ? point : best),
    { seatRate: 0, minute: 0, queue: 0 }
  )
  const crowdedPoints = timeline.filter((point) => point.seatRate >= 0.7)
  return {
    timeline,
    peak,
    crowdedCount: crowdedPoints.length,
    crowdedRatio: timeline.length === 0 ? 0 : crowdedPoints.length / timeline.length,
    maxQueue: Math.max(0, ...timeline.map((point) => point.queue))
  }
}
