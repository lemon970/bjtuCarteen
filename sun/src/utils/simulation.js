import { DEFAULT_FORM, MAX_RENDERED_SEATS, MAX_SEATS, MAX_STUDENTS } from '../constants'

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


export function buildSeatCells(point, fallbackCells = []) {
  const totalSeats = Math.max(0, Math.floor(toNumber(read(point, 'total_seats', 'totalSeats'), 0)))
  const occupiedSeats = Math.max(0, Math.floor(toNumber(read(point, 'occupied_seats', 'occupiedSeats', 'dining_student_count', 'diningStudentCount'), 0)))
  const sourceCells = read(point, 'seat_cells', 'seatCells')
  if (Array.isArray(sourceCells) && sourceCells.length > 0) {
    return compactSeatCells(sourceCells, totalSeats || sourceCells.length, occupiedSeats)
  }

  const layout = read(point, 'frame_seat_layout', 'frameSeatLayout')
  const tables = Array.isArray(layout) && layout.length > 0
    ? layout
    : read(point, 'table_snapshots', 'tableSnapshots')
  if (Array.isArray(tables) && tables.length > 0) {
    const cellsFromTables = expandTablesToCells(tables, fallbackCells)
    if (cellsFromTables.length > 0) {
      const projectedTotal = totalSeats || cellsFromTables.length
      const projectedOccupied = cellsFromTables.filter((cell) => cell.status === 'OCCUPIED').length
      return compactSeatCells(cellsFromTables, projectedTotal, projectedOccupied || occupiedSeats)
    }
  }

  if (Array.isArray(fallbackCells) && fallbackCells.length > 0) {
    const occupiedLimit = Math.max(0, Math.floor(toNumber(occupiedSeats, 0)))
    const projected = fallbackCells.map((cell, index) => ({
      ...cell,
      status: index < occupiedLimit ? 'OCCUPIED' : read(cell, 'status') || 'FREE',
      occupied: index < occupiedLimit,
      group_id: read(cell, 'group_id', 'groupId') || ''
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
      occupied: index < occupiedSeats,
      group_id: ''
  })), totalSeats, occupiedSeats)
}

function expandTablesToCells(tableSnapshots, fallbackCells) {
  const fallback = Array.isArray(fallbackCells) ? fallbackCells : []
  const fallbackByTable = new Map()
  for (const cell of fallback) {
    const tableId = read(cell, 'table_id', 'tableId')
    if (tableId === undefined || tableId === null) {
      continue
    }
    const list = fallbackByTable.get(tableId) || []
    list.push(cell)
    fallbackByTable.set(tableId, list)
  }

  // 第十轮 C3:稀疏 frame_seat_layout 兼容 — 缺失的 tableId 视为空桌
  const presentTableIds = new Set()
  for (const t of tableSnapshots) {
    const id = read(t, 'table_id', 'tableId')
    if (id !== undefined && id !== null) presentTableIds.add(id)
  }
  const missingFromLayout = []
  for (const [tableId, cells] of fallbackByTable.entries()) {
    if (presentTableIds.has(tableId)) continue
    missingFromLayout.push({
      table_id: tableId,
      capacity: cells.length,
      occupied_seats: 0,
      reserved_seats: 0,
      occupied_group_ids: [],
      reserved_group_ids: []
    })
  }
  const allSnapshots = [...tableSnapshots, ...missingFromLayout]

  const cells = []
  let seatId = 0
  for (const table of allSnapshots) {
    const tableId = read(table, 'table_id', 'tableId') ?? -1
    const capacity = Math.max(0, Math.floor(toNumber(read(table, 'capacity'), 0)))
    const occupied = Math.max(0, Math.min(capacity, Math.floor(toNumber(read(table, 'occupied_seats', 'occupiedSeats'), 0))))
    const reserved = Math.max(
      0,
      Math.min(capacity - occupied, Math.floor(toNumber(read(table, 'reserved_seats', 'reservedSeats'), 0)))
    )
    const occupiedIds = read(table, 'occupied_group_ids', 'occupiedGroupIds')
    const occupiedList = Array.isArray(occupiedIds) ? occupiedIds : []
    const reservedIds = read(table, 'reserved_group_ids', 'reservedGroupIds')
    const reservedList = Array.isArray(reservedIds) ? reservedIds : []
    const fallbackList = fallbackByTable.get(tableId) || []
    for (let seatIndex = 0; seatIndex < capacity; seatIndex++) {
      const fallbackCell = fallbackList[seatIndex] || {}
      let status = 'FREE'
      let groupId = ''
      const isOccupied = seatIndex < occupied
      const isReserved = !isOccupied && seatIndex < occupied + reserved
      if (isOccupied) {
        status = 'OCCUPIED'
        const fromTable = occupiedList[seatIndex]
        if (fromTable !== undefined && fromTable !== null) {
          groupId = String(fromTable)
        } else {
          groupId = String(read(fallbackCell, 'group_id', 'groupId') || '')
        }
      } else if (isReserved) {
        status = 'RESERVED'
        const fromReserved = reservedList[seatIndex - occupied]
        if (fromReserved !== undefined && fromReserved !== null) {
          groupId = String(fromReserved)
        }
      }
      cells.push({
        seat_id: read(fallbackCell, 'seat_id', 'seatId') ?? seatId,
        table_id: tableId,
        row: read(fallbackCell, 'row') ?? Math.floor(seatId / 12),
        column: read(fallbackCell, 'column') ?? (seatId % 12),
        area: read(fallbackCell, 'area') || ['A', 'B', 'C'][Math.max(0, Number(tableId) || 0) % 3],
        status,
        occupied: status === 'OCCUPIED',
        reserved: status === 'RESERVED',
        group_id: groupId
      })
      seatId++
    }
  }
  return cells
}

function compactSeatCells(cells, totalSeats, occupiedSeats) {
  const source = Array.isArray(cells) ? cells : []
  const safeTotal = Math.max(source.length, Math.floor(toNumber(totalSeats, source.length)))
  if (source.length <= MAX_RENDERED_SEATS) {
    return source
  }

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
      group_id: index < renderedOccupied ? read(sampled, 'group_id', 'groupId') || '' : '',
      sampled: true,
      total_seats: safeTotal
    }
  })
}

export function buildSeatTables(point, fallbackCells = []) {
  const layout = read(point, 'frame_seat_layout', 'frameSeatLayout')
  const tables = Array.isArray(layout) && layout.length > 0
    ? layout
    : read(point, 'table_snapshots', 'tableSnapshots')
  if (Array.isArray(tables) && tables.length > 0) {
    const fallback = Array.isArray(fallbackCells) ? fallbackCells : []
    const fallbackByTable = new Map()
    for (const cell of fallback) {
      const tableId = read(cell, 'table_id', 'tableId')
      if (tableId === undefined || tableId === null) {
        continue
      }
      const list = fallbackByTable.get(tableId) || []
      list.push(cell)
      fallbackByTable.set(tableId, list)
    }

    // 第十轮 C3:后端 frame_seat_layout 已稀疏化(空桌不发送)。
    // 前端从 fallbackCells 推断完整 tableId 列表,缺失的桌子按"空桌"补齐。
    const presentTableIds = new Set()
    for (const t of tables) {
      const id = read(t, 'table_id', 'tableId')
      if (id !== undefined && id !== null) presentTableIds.add(id)
    }
    const missingFromLayout = []
    for (const [tableId, cells] of fallbackByTable.entries()) {
      if (presentTableIds.has(tableId)) continue
      missingFromLayout.push({
        table_id: tableId,
        capacity: cells.length,
        occupied_seats: 0,
        reserved_seats: 0,
        occupied_group_ids: [],
        reserved_group_ids: []
      })
    }
    const allTables = [...tables, ...missingFromLayout]

    const result = []
    let seatCounter = 0
    for (const table of allTables) {
      const tableId = read(table, 'table_id', 'tableId') ?? -1
      const capacity = Math.max(0, Math.floor(toNumber(read(table, 'capacity'), 0)))
      const occupied = Math.max(
        0,
        Math.min(capacity, Math.floor(toNumber(read(table, 'occupied_seats', 'occupiedSeats'), 0)))
      )
      const reserved = Math.max(
        0,
        Math.min(capacity - occupied, Math.floor(toNumber(read(table, 'reserved_seats', 'reservedSeats'), 0)))
      )
      const groupIds = read(table, 'occupied_group_ids', 'occupiedGroupIds')
      const groupList = Array.isArray(groupIds) ? groupIds : []
      const reservedGroupIds = read(table, 'reserved_group_ids', 'reservedGroupIds')
      const reservedList = Array.isArray(reservedGroupIds) ? reservedGroupIds : []
      const fallbackList = fallbackByTable.get(tableId) || []
      const seats = []
      for (let seatIndex = 0; seatIndex < capacity; seatIndex++) {
        const fallbackCell = fallbackList[seatIndex] || {}
        const isOccupied = seatIndex < occupied
        const isReserved = !isOccupied && seatIndex < occupied + reserved
        let status = 'FREE'
        let groupId = ''
        if (isOccupied) {
          status = 'OCCUPIED'
          const fromTable = groupList[seatIndex]
          if (fromTable !== undefined && fromTable !== null) {
            groupId = String(fromTable)
          }
        } else if (isReserved) {
          status = 'RESERVED'
          const fromReserved = reservedList[seatIndex - occupied]
          if (fromReserved !== undefined && fromReserved !== null) {
            groupId = String(fromReserved)
          }
        }
        seats.push({
          seatId: read(fallbackCell, 'seat_id', 'seatId') ?? seatCounter,
          status,
          occupied: isOccupied,
          reserved: isReserved,
          groupId,
          tableId,
          row: read(fallbackCell, 'row') ?? Math.floor(seatCounter / 12),
          column: read(fallbackCell, 'column') ?? (seatCounter % 12)
        })
        seatCounter++
      }
      result.push({
        tableId,
        capacity,
        occupied,
        reserved,
        area: read(fallbackList[0], 'area') || ['A', 'B', 'C'][Math.max(0, Number(tableId) || 0) % 3],
        seats
      })
    }
    return result
  }

  // Fallback: derive tables from flat seat cells (legacy path)
  const cells = buildSeatCells(point, fallbackCells)
  if (!cells.length) return []
  const tableMap = new Map()
  let counter = 0
  for (const cell of cells) {
    const tableId = read(cell, 'table_id', 'tableId') ?? Math.floor(counter / 4)
    counter++
    const entry = tableMap.get(tableId) || {
      tableId,
      capacity: 0,
      occupied: 0,
      area: read(cell, 'area') || 'A',
      seats: []
    }
    const status = String(read(cell, 'status') || 'FREE').toUpperCase()
    const isOccupied = status === 'OCCUPIED' || read(cell, 'occupied') === true
    entry.seats.push({
      seatId: read(cell, 'seat_id', 'seatId') ?? entry.seats.length,
      status: isOccupied ? 'OCCUPIED' : status,
      occupied: isOccupied,
      groupId: isOccupied ? String(read(cell, 'group_id', 'groupId') || '') : '',
      tableId,
      row: read(cell, 'row') ?? 0,
      column: read(cell, 'column') ?? 0
    })
    entry.capacity += 1
    if (isOccupied) entry.occupied += 1
    tableMap.set(tableId, entry)
  }
  return [...tableMap.values()]
}

export function summarizeGroupsOnFrame(tables) {
  const groupMap = new Map()
  for (const table of Array.isArray(tables) ? tables : []) {
    for (const seat of table.seats || []) {
      if (!seat.occupied || !seat.groupId) continue
      const entry = groupMap.get(seat.groupId) || {
        groupId: seat.groupId,
        seatCount: 0,
        tableIds: new Set()
      }
      entry.seatCount += 1
      entry.tableIds.add(table.tableId)
      groupMap.set(seat.groupId, entry)
    }
  }
  return [...groupMap.values()]
    .map((entry) => ({
      groupId: entry.groupId,
      seatCount: entry.seatCount,
      tableIds: [...entry.tableIds],
      tableCount: entry.tableIds.size
    }))
    .sort((a, b) => b.seatCount - a.seatCount)
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

export function summarizeSeatCells(cells) {
  const summary = { occupied: 0, reserved: 0, cleaning: 0, free: 0, grouped: 0, total: 0 }
  for (const cell of Array.isArray(cells) ? cells : []) {
    summary.total += 1
    const status = String(read(cell, 'status') || '').toUpperCase()
    if (status === 'OCCUPIED' || read(cell, 'occupied') === true) {
      summary.occupied += 1
    } else if (status === 'RESERVED' || read(cell, 'reserved') === true) {
      summary.reserved += 1
    } else if (status === 'CLEANING') {
      summary.cleaning += 1
    } else {
      summary.free += 1
    }
    if (read(cell, 'group_id', 'groupId') && (status === 'OCCUPIED' || read(cell, 'occupied') === true)) {
      summary.grouped += 1
    }
  }
  return summary
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

  const payload = {
    simulation_name: String(form.simulationName || DEFAULT_FORM.simulationName),
    duration: Math.max(0.1, toNumber(form.duration, DEFAULT_FORM.duration)),
    arrival_rate: arrivalRate,
    queue_limit: Math.max(0, Math.floor(toNumber(form.queueLimit, DEFAULT_FORM.queueLimit))),
    pack_probability: clamp(toNumber(form.packProbability, DEFAULT_FORM.packProbability), 0, 1),
    group_arrival_prob: clamp(toNumber(form.groupArrivalProb, DEFAULT_FORM.groupArrivalProb), 0, 1),
    party_size: Math.max(1, Math.floor(toNumber(form.partySize, DEFAULT_FORM.partySize))),
    group_config: {
      enabled: Boolean(form.groupEnabled),
      group_count: Math.max(0, Math.floor(toNumber(form.groupCount, DEFAULT_FORM.groupCount))),
      size_min: Math.max(1, Math.floor(toNumber(form.groupSizeMin, DEFAULT_FORM.groupSizeMin))),
      size_max: Math.max(1, Math.floor(toNumber(form.groupSizeMax, DEFAULT_FORM.groupSizeMax))),
      arrival_spread_seconds: Math.max(
        0,
        Math.floor(toNumber(form.groupArrivalSpreadSeconds, DEFAULT_FORM.groupArrivalSpreadSeconds))
      ),
      behavior_correlation: clamp(
        toNumber(form.groupBehaviorCorrelation, DEFAULT_FORM.groupBehaviorCorrelation),
        0,
        1
      ),
      prefer_adjacent_seats: Boolean(form.preferAdjacentSeats)
    },
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
  const groupConfig = read(payload, 'groupConfig', 'group_config') || {}
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
    groupEnabled: read(groupConfig, 'enabled') ?? DEFAULT_FORM.groupEnabled,
    groupCount: read(groupConfig, 'groupCount', 'group_count') ?? DEFAULT_FORM.groupCount,
    groupSizeMin: read(groupConfig, 'sizeMin', 'size_min') ?? DEFAULT_FORM.groupSizeMin,
    groupSizeMax: read(groupConfig, 'sizeMax', 'size_max') ?? DEFAULT_FORM.groupSizeMax,
    groupArrivalSpreadSeconds:
      read(groupConfig, 'arrivalSpreadSeconds', 'arrival_spread_seconds') ?? DEFAULT_FORM.groupArrivalSpreadSeconds,
    groupBehaviorCorrelation:
      read(groupConfig, 'behaviorCorrelation', 'behavior_correlation') ?? DEFAULT_FORM.groupBehaviorCorrelation,
    preferAdjacentSeats: read(groupConfig, 'preferAdjacentSeats', 'prefer_adjacent_seats') ?? DEFAULT_FORM.preferAdjacentSeats,
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
    cleaningSeats: read(point, 'cleaning_seats', 'cleaningSeats') ?? 0,
    reservedSeats: read(point, 'reserved_seats', 'reservedSeats') ?? 0,
    totalSeats: read(point, 'total_seats', 'totalSeats') ?? 0,
    seatCells: read(point, 'seat_cells', 'seatCells') || [],
    seatRate: read(point, 'seat_utilization_rate', 'seatUtilizationRate') ?? 0,
    seatUnavailableRate: read(point, 'seat_unavailable_rate', 'seatUnavailableRate') ?? 0,
    seatReservedShare: read(point, 'seat_reserved_share', 'seatReservedShare') ?? 0,
    seatFreeRate: read(point, 'seat_free_rate', 'seatFreeRate') ?? 0,
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
