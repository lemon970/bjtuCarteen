import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

import HistoricalQualityCard from './HistoricalQualityCard'

vi.mock('../utils/useEcharts', () => ({
  useEcharts: () => ({ current: null })
}))

const baseDimensions = {
  availability: { score: 0.9 },
  comparability: { score: 0.85 },
  historical_conformity: { score: 0.8 },
  reliability: { score: 0.75 }
}

function buildAvailableQuality(overrides = {}) {
  return {
    enabled: true,
    schema_version: '1.1',
    computed_by: 'java-quality-scorer',
    score_available: true,
    quality_score: 0.825,
    quality_score_percent: 82.5,
    level: 'good',
    dimensions: { ...baseDimensions },
    penalties: [],
    warnings: ['QUALITY_SCORE_IS_DIAGNOSTIC_ONLY', 'NOT_A_BUSINESS_PERFORMANCE_SCORE'],
    basis: {
      diagnostics_used: true,
      corpus_size: 12,
      matched_reports: 8,
      baseline_confidence: 'high',
      baseline_strategy: 'scenario_id_exact'
    },
    ...overrides
  }
}

describe('HistoricalQualityCard', () => {
  it('quality=null 不渲染任何卡片节点', () => {
    const { container } = render(<HistoricalQualityCard quality={null} />)
    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByTestId('historical-quality-card')).toBeNull()
  })

  it('score_available=false 时显示 level=unavailable + reason,不显示分数', () => {
    const quality = {
      enabled: true,
      schema_version: '1.0',
      computed_by: 'java-quality-scorer',
      score_available: false,
      level: 'unavailable',
      unavailable_reason: 'MISSING_SUMMARY',
      warnings: ['QUALITY_SCORE_IS_DIAGNOSTIC_ONLY', 'NOT_A_BUSINESS_PERFORMANCE_SCORE'],
      basis: { diagnostics_used: true }
    }
    render(<HistoricalQualityCard quality={quality} />)

    expect(screen.getByTestId('historical-quality-card')).toBeInTheDocument()
    expect(screen.getByTestId('historical-quality-level').textContent).toMatch(/不可用/)

    const reasonNode = screen.getByTestId('historical-quality-unavailable-reason')
    expect(reasonNode.textContent).toMatch(/MISSING_SUMMARY/)

    expect(screen.queryByTestId('historical-quality-percent')).toBeNull()
    expect(screen.queryByTestId('historical-quality-dimensions')).toBeNull()
    expect(screen.queryByTestId('historical-quality-radar')).toBeNull()

    const disclaimer = screen.getByTestId('historical-quality-disclaimer')
    expect(disclaimer).toBeInTheDocument()
    expect(screen.getByTestId('hq-warning-QUALITY_SCORE_IS_DIAGNOSTIC_ONLY')).toBeInTheDocument()
    expect(screen.getByTestId('hq-warning-NOT_A_BUSINESS_PERFORMANCE_SCORE')).toBeInTheDocument()
  })

  it('score_available=true 时显示分数 + 4 维度 + 雷达图 + 免责声明', () => {
    const quality = {
      enabled: true,
      schema_version: '1.0',
      computed_by: 'java-quality-scorer',
      score_available: true,
      quality_score: 0.825,
      quality_score_percent: 82.5,
      level: 'good',
      dimensions: {
        availability: { score: 0.9 },
        comparability: { score: 0.85 },
        historical_conformity: { score: 0.8 },
        reliability: { score: 0.75 }
      },
      penalties: [{ reason: 'INSUFFICIENT_HISTORY', amount: 0.05 }],
      warnings: ['QUALITY_SCORE_IS_DIAGNOSTIC_ONLY', 'NOT_A_BUSINESS_PERFORMANCE_SCORE'],
      basis: { diagnostics_used: true, corpus_size: 12, matched_reports: 8 }
    }
    render(<HistoricalQualityCard quality={quality} />)

    expect(screen.getByTestId('historical-quality-card')).toBeInTheDocument()
    expect(screen.getByTestId('historical-quality-level').textContent).toMatch(/良好/)

    expect(screen.getByTestId('historical-quality-percent').textContent).toBe('82.5')

    expect(screen.getByTestId('hq-dim-availability')).toBeInTheDocument()
    expect(screen.getByTestId('hq-dim-comparability')).toBeInTheDocument()
    expect(screen.getByTestId('hq-dim-historical_conformity')).toBeInTheDocument()
    expect(screen.getByTestId('hq-dim-reliability')).toBeInTheDocument()

    expect(screen.getByTestId('historical-quality-radar')).toBeInTheDocument()
    expect(screen.getByTestId('historical-quality-penalties')).toBeInTheDocument()

    expect(screen.getByTestId('hq-warning-QUALITY_SCORE_IS_DIAGNOSTIC_ONLY')).toBeInTheDocument()
    expect(screen.getByTestId('hq-warning-NOT_A_BUSINESS_PERFORMANCE_SCORE')).toBeInTheDocument()
  })

  // ==================== F1 ====================
  it('F1: confidence=high 显示"强历史基线" chip,不显示 banner', () => {
    const quality = buildAvailableQuality({
      basis: {
        diagnostics_used: true,
        corpus_size: 12,
        matched_reports: 10,
        baseline_confidence: 'high',
        baseline_strategy: 'scenario_id_exact'
      }
    })
    render(<HistoricalQualityCard quality={quality} />)

    const chip = screen.getByTestId('hq-confidence-chip-high')
    expect(chip).toBeInTheDocument()
    expect(chip.textContent).toMatch(/强历史基线/)

    expect(screen.queryByTestId('hq-confidence-banner-high')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-banner-low')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-banner-very_low')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-banner-none')).toBeNull()
  })

  // ==================== F2 ====================
  it('F2: confidence=medium 显示"中等历史基线" chip,不显示 banner', () => {
    const quality = buildAvailableQuality({
      basis: {
        diagnostics_used: true,
        corpus_size: 12,
        matched_reports: 6,
        baseline_confidence: 'medium',
        baseline_strategy: 'scenario_id_exact'
      }
    })
    render(<HistoricalQualityCard quality={quality} />)

    const chip = screen.getByTestId('hq-confidence-chip-medium')
    expect(chip).toBeInTheDocument()
    expect(chip.textContent).toMatch(/中等历史基线/)

    expect(screen.queryByTestId('hq-confidence-banner-medium')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-banner-low')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-banner-very_low')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-banner-none')).toBeNull()
  })

  // ==================== F3 ====================
  it('F3: confidence=low 显示"弱历史基线" chip + 横幅', () => {
    const quality = buildAvailableQuality({
      basis: {
        diagnostics_used: true,
        corpus_size: 12,
        matched_reports: 3,
        baseline_confidence: 'low',
        baseline_strategy: 'weighted_nearest_neighbors'
      }
    })
    render(<HistoricalQualityCard quality={quality} />)

    const chip = screen.getByTestId('hq-confidence-chip-low')
    expect(chip).toBeInTheDocument()
    expect(chip.textContent).toMatch(/弱历史基线/)

    const banner = screen.getByTestId('hq-confidence-banner-low')
    expect(banner).toBeInTheDocument()
    expect(banner.textContent).toMatch(/历史基线较弱/)
  })

  // ==================== F4 ====================
  it('F4: confidence=very_low + historical_conformity.not_applicable=true 时显示 chip/banner 且维度显示"—"和"不适用"', () => {
    const quality = buildAvailableQuality({
      dimensions: {
        availability: { score: 0.9 },
        comparability: { score: 0.7 },
        historical_conformity: {
          score: null,
          not_applicable: true,
          excluded_from_min: true
        },
        reliability: { score: 0.75 }
      },
      basis: {
        diagnostics_used: true,
        corpus_size: 12,
        matched_reports: 0,
        baseline_confidence: 'very_low',
        baseline_strategy: 'global_reference_baseline'
      }
    })
    render(<HistoricalQualityCard quality={quality} />)

    const chip = screen.getByTestId('hq-confidence-chip-very_low')
    expect(chip).toBeInTheDocument()
    expect(chip.textContent).toMatch(/全局参考/)

    const banner = screen.getByTestId('hq-confidence-banner-very_low')
    expect(banner).toBeInTheDocument()
    expect(banner.textContent).toMatch(/全局参考/)

    const dimNode = screen.getByTestId('hq-dim-historical_conformity')
    expect(dimNode).toBeInTheDocument()
    expect(dimNode.textContent).toMatch(/—/)
    expect(dimNode.textContent).toMatch(/不适用/)
    expect(dimNode.textContent).not.toMatch(/0%/)

    expect(screen.getByTestId('hq-dim-availability').textContent).toMatch(/90%/)
    expect(screen.getByTestId('hq-dim-comparability').textContent).toMatch(/70%/)
    expect(screen.getByTestId('hq-dim-reliability').textContent).toMatch(/75%/)
  })

  // ==================== F5 ====================
  it('F5: confidence=none + score_available=false 显示 chip "暂无可比历史" + 横幅', () => {
    const quality = {
      enabled: true,
      schema_version: '1.1',
      computed_by: 'java-quality-scorer',
      score_available: false,
      level: 'unavailable',
      unavailable_reason: 'INSUFFICIENT_HISTORY',
      warnings: ['QUALITY_SCORE_IS_DIAGNOSTIC_ONLY', 'NOT_A_BUSINESS_PERFORMANCE_SCORE'],
      basis: {
        diagnostics_used: true,
        corpus_size: 0,
        matched_reports: 0,
        baseline_confidence: 'none',
        baseline_strategy: 'none'
      }
    }
    render(<HistoricalQualityCard quality={quality} />)

    expect(screen.getByTestId('historical-quality-card')).toBeInTheDocument()

    const chip = screen.getByTestId('hq-confidence-chip-none')
    expect(chip).toBeInTheDocument()
    expect(chip.textContent).toMatch(/暂无可比历史/)

    const banner = screen.getByTestId('hq-confidence-banner-none')
    expect(banner).toBeInTheDocument()
    expect(banner.textContent).toMatch(/暂无可比历史/)

    expect(screen.queryByTestId('historical-quality-percent')).toBeNull()
    expect(screen.queryByTestId('historical-quality-radar')).toBeNull()
  })

  // ==================== F5b ====================
  it('F5b: score_available=true + confidence=none + historical_conformity.not_applicable=true 仍然 chip+banner+维度"—"+免责声明', () => {
    const quality = {
      enabled: true,
      schema_version: '1.1',
      computed_by: 'java-quality-scorer',
      score_available: true,
      quality_score: 0.50,
      quality_score_percent: 50.0,
      level: 'fair',
      dimensions: {
        availability: { score: 0.95 },
        comparability: { score: 0.50 },
        historical_conformity: {
          score: null,
          not_applicable: true,
          excluded_from_min: true
        },
        reliability: { score: 0.95 }
      },
      penalties: [],
      warnings: ['QUALITY_SCORE_IS_DIAGNOSTIC_ONLY', 'NOT_A_BUSINESS_PERFORMANCE_SCORE'],
      basis: {
        diagnostics_used: true,
        corpus_size: 12,
        matched_reports: 0,
        baseline_confidence: 'none',
        baseline_strategy: 'none'
      }
    }
    const { container } = render(<HistoricalQualityCard quality={quality} />)

    const chip = screen.getByTestId('hq-confidence-chip-none')
    expect(chip).toBeInTheDocument()
    expect(chip.textContent).toMatch(/暂无可比历史/)

    const banner = screen.getByTestId('hq-confidence-banner-none')
    expect(banner).toBeInTheDocument()
    expect(banner.textContent).toMatch(/暂无可比历史记录,无法进行历史可比性判断/)

    const dimNode = screen.getByTestId('hq-dim-historical_conformity')
    expect(dimNode).toBeInTheDocument()
    expect(dimNode.textContent).toMatch(/—/)
    expect(dimNode.textContent).toMatch(/不适用/)
    expect(dimNode.textContent).not.toMatch(/0%/)

    const wholeText = container.textContent || ''
    expect(wholeText).not.toMatch(/质量差/)
    expect(wholeText).not.toMatch(/结果差/)
    expect(wholeText).not.toMatch(/表现差/)

    expect(screen.getByTestId('historical-quality-disclaimer')).toBeInTheDocument()
    expect(screen.getByTestId('hq-warning-QUALITY_SCORE_IS_DIAGNOSTIC_ONLY')).toBeInTheDocument()
    expect(screen.getByTestId('hq-warning-NOT_A_BUSINESS_PERFORMANCE_SCORE')).toBeInTheDocument()
  })

  // ==================== F6 ====================
  it('F6: confidence=unknown 或 basis 缺失时不显示 chip 也不显示 banner(legacy 兼容)', () => {
    // 6a: basis.baseline_confidence 缺失(legacy 1.0)
    const legacy = {
      enabled: true,
      schema_version: '1.0',
      computed_by: 'java-quality-scorer',
      score_available: true,
      quality_score: 0.82,
      quality_score_percent: 82.0,
      level: 'good',
      dimensions: { ...baseDimensions },
      penalties: [],
      warnings: ['QUALITY_SCORE_IS_DIAGNOSTIC_ONLY', 'NOT_A_BUSINESS_PERFORMANCE_SCORE'],
      basis: { diagnostics_used: true, corpus_size: 12, matched_reports: 8 }
    }
    const { unmount } = render(<HistoricalQualityCard quality={legacy} />)

    expect(screen.queryByTestId('hq-confidence-chip-high')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-chip-medium')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-chip-low')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-chip-very_low')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-chip-none')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-chip-unknown')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-banner-low')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-banner-very_low')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-banner-none')).toBeNull()

    // 旧展示路径仍正常工作
    expect(screen.getByTestId('historical-quality-percent').textContent).toBe('82.0')
    unmount()

    // 6b: 显式 baseline_confidence='unknown'
    const unknown = buildAvailableQuality({
      basis: {
        diagnostics_used: true,
        corpus_size: 12,
        matched_reports: 8,
        baseline_confidence: 'unknown',
        baseline_strategy: 'unknown'
      }
    })
    render(<HistoricalQualityCard quality={unknown} />)

    expect(screen.queryByTestId('hq-confidence-chip-unknown')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-banner-low')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-banner-very_low')).toBeNull()
    expect(screen.queryByTestId('hq-confidence-banner-none')).toBeNull()
  })
})
