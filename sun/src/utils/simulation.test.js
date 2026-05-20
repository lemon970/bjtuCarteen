import { describe, it, expect } from 'vitest'

import { buildSeatCells, summarizeSeatAreas, summarizeSeatCells } from './simulation'
import { MAX_RENDERED_SEATS } from '../constants'

const AREAS = ['A', 'B', 'C', 'D']

function makeCell(seatId, { area, status = 'FREE', tableId, row, column, groupId } = {}) {
  return {
    seat_id: seatId,
    table_id: tableId ?? Math.floor(seatId / 4),
    row: row ?? Math.floor(seatId / 12),
    column: column ?? (seatId % 12),
    area: area ?? AREAS[Math.floor(seatId / 100) % AREAS.length],
    status,
    occupied: status === 'OCCUPIED',
    group_id: groupId ?? ''
  }
}

function makeSource(size, occupiedRatio = 0.4) {
  const occupiedThreshold = Math.floor(size * occupiedRatio)
  return Array.from({ length: size }, (_, idx) => {
    // 真实空间分布:A 区前 200 全占用,其余 free,B/C/D 各自分散占用
    let status = 'FREE'
    const area = AREAS[Math.floor(idx / Math.ceil(size / 4)) % AREAS.length]
    if (area === 'A' && idx < Math.min(200, occupiedThreshold)) {
      status = 'OCCUPIED'
    } else if (area === 'C' && (idx % 5 === 0)) {
      status = 'OCCUPIED'
    } else if (area === 'D' && idx > size - 30) {
      status = 'OCCUPIED'
    }
    return makeCell(idx, { area, status, tableId: Math.floor(idx / 4) })
  })
}

describe('compactSeatCells via buildSeatCells', () => {
  it('source cells <= MAX_RENDERED_SEATS 时直接返回不改变数据', () => {
    const source = Array.from({ length: 100 }, (_, i) => makeCell(i, { area: 'A' }))
    const point = { total_seats: 100, occupied_seats: 0, seat_cells: source }
    const result = buildSeatCells(point)
    expect(result).toBe(source) // 引用相等,未拷贝
    expect(result).toHaveLength(100)
  })

  it('source cells > MAX_RENDERED_SEATS 时抽样到 360 但保留 area/status/occupied/table_id', () => {
    const source = makeSource(800)
    const point = { total_seats: 800, occupied_seats: 220, seat_cells: source }
    const result = buildSeatCells(point)
    expect(result).toHaveLength(MAX_RENDERED_SEATS)

    // 不再覆盖 area:每条抽样必须等于其 source cell 的 area
    for (const cell of result) {
      const idx = cell.sample_source_index
      expect(typeof idx).toBe('number')
      expect(cell.area).toBe(source[idx].area)
      expect(cell.status).toBe(source[idx].status)
      expect(cell.occupied).toBe(source[idx].occupied)
      expect(cell.table_id).toBe(source[idx].table_id)
      expect(cell.row).toBe(source[idx].row)
      expect(cell.column).toBe(source[idx].column)
    }
  })

  it('不再出现"前 N 个 OCCUPIED 后面 FREE"的伪造模式', () => {
    const source = makeSource(800, 0.4)
    const point = { total_seats: 800, occupied_seats: 320, seat_cells: source }
    const result = buildSeatCells(point)

    // 真实分布是 A 区前 200 occupied + C 区每 5 个 + D 区尾部 30 个,
    // 抽样后不应在前缀连续出现 OCCUPIED 而后缀连续 FREE。
    const firstOccupiedIdx = result.findIndex((c) => c.occupied)
    const lastFreeIdx = (() => {
      for (let i = result.length - 1; i >= 0; i--) if (!result[i].occupied) return i
      return -1
    })()

    // 必有交替段:占用区在数组里不能完全前置,空闲区也不会完全后置
    const hasFreeBeforeOccupiedTail = result.some((c, idx) =>
      !c.occupied && idx > firstOccupiedIdx && idx < lastFreeIdx)
    expect(hasFreeBeforeOccupiedTail).toBe(true)

    // 同时:抽样后 D 区(原本只在尾部 occupied)的 occupied 必须保留下来
    const dOccupied = result.filter((c) => c.area === 'D' && c.occupied)
    expect(dOccupied.length).toBeGreaterThan(0)
  })

  it('同 source 两次抽样的 source 索引完全一致(稳定步长)', () => {
    const source = makeSource(800)
    const point = { total_seats: 800, occupied_seats: 100, seat_cells: source }
    const r1 = buildSeatCells(point)
    const r2 = buildSeatCells(point)
    const idx1 = r1.map((c) => c.sample_source_index)
    const idx2 = r2.map((c) => c.sample_source_index)
    expect(idx1).toEqual(idx2)
  })

  it('summarizeSeatCells/summarizeSeatAreas 走预聚合,不报告抽样后的 360 当作总数', () => {
    const size = 800
    const source = makeSource(size, 0.4)
    const point = { total_seats: size, occupied_seats: 320, seat_cells: source }
    const compacted = buildSeatCells(point)
    expect(compacted).toHaveLength(MAX_RENDERED_SEATS)

    const summary = summarizeSeatCells(compacted)
    expect(summary.total).toBe(size) // 原始 800 而不是 360
    // 真实占用数等于 source 中 status=OCCUPIED 的真实计数
    const trueOccupied = source.filter((c) => c.status === 'OCCUPIED').length
    expect(summary.occupied).toBe(trueOccupied)

    const areas = summarizeSeatAreas(compacted)
    const areaTotalSum = areas.reduce((acc, a) => acc + a.total, 0)
    expect(areaTotalSum).toBe(size) // 区域聚合也走源全集
    // 至少 A/B/C/D 中实际存在的区域都要出现
    const areaNames = areas.map((a) => a.area).sort()
    expect(areaNames).toEqual(expect.arrayContaining(['A', 'C', 'D']))
  })

  it('summarizeSeatCells 在未抽样数据上回退到正常逐 cell 聚合', () => {
    const small = [
      makeCell(0, { area: 'A', status: 'OCCUPIED' }),
      makeCell(1, { area: 'B', status: 'FREE' }),
      makeCell(2, { area: 'B', status: 'RESERVED' })
    ]
    const summary = summarizeSeatCells(small)
    expect(summary.total).toBe(3)
    expect(summary.occupied).toBe(1)
    expect(summary.reserved).toBe(1)
    expect(summary.free).toBe(1)
  })
})
