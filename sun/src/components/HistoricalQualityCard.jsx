import { useMemo } from 'react'

import { useEcharts } from '../utils/useEcharts'

const LEVEL_LABEL = {
  excellent: '优秀',
  good: '良好',
  fair: '一般',
  poor: '较差',
  unavailable: '不可用'
}

const LEVEL_TONE = {
  excellent: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  good: 'bg-sky-50 text-sky-700 border-sky-200',
  fair: 'bg-amber-50 text-amber-700 border-amber-200',
  poor: 'bg-rose-50 text-rose-700 border-rose-200',
  unavailable: 'bg-slate-100 text-slate-600 border-slate-200'
}

const DIMENSION_LABEL = {
  availability: '数据可得性',
  comparability: '历史可比性',
  historical_conformity: '历史一致性',
  reliability: '数据可靠性'
}

const DIMENSION_ORDER = ['availability', 'comparability', 'historical_conformity', 'reliability']

const REASON_LABEL = {
  MISSING_SUMMARY: '缺少当前报告摘要',
  INSUFFICIENT_HISTORY: '历史样本不足',
  DIAGNOSTICS_ERROR: '诊断计算异常',
  SCORER_ERROR: '评分计算异常'
}

const WARNING_LABEL = {
  QUALITY_SCORE_IS_DIAGNOSTIC_ONLY: '此评分仅用于数据质量诊断',
  NOT_A_BUSINESS_PERFORMANCE_SCORE: '不代表业务/餐厅表现'
}

const CONFIDENCE_LABEL = {
  high: '强历史基线',
  medium: '中等历史基线',
  low: '弱历史基线',
  very_low: '全局参考',
  none: '暂无可比历史'
}

const CONFIDENCE_TONE = {
  high: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  medium: 'bg-sky-50 text-sky-700 border-sky-200',
  low: 'bg-amber-50 text-amber-700 border-amber-200',
  very_low: 'bg-orange-50 text-orange-700 border-orange-200',
  none: 'bg-rose-50 text-rose-700 border-rose-200'
}

const BANNER_TEXT = {
  low: '历史基线较弱,评分仅供参考。',
  very_low: '缺少相似历史记录,仅使用全局参考。本结果不构成异常判定。',
  none: '暂无可比历史记录,无法进行历史可比性判断。'
}

function deriveConfidence(quality) {
  const raw = quality?.basis?.baseline_confidence
  if (typeof raw !== 'string' || !raw) return 'unknown'
  return raw
}

function levelClass(level) {
  return LEVEL_TONE[level] || LEVEL_TONE.unavailable
}

function levelLabel(level) {
  return LEVEL_LABEL[level] || level || '未知'
}

function reasonText(code) {
  if (!code) return ''
  return REASON_LABEL[code] ? `${REASON_LABEL[code]} (${code})` : code
}

function warningText(code) {
  return WARNING_LABEL[code] ? `${WARNING_LABEL[code]} (${code})` : code
}

function ConfidenceChip({ confidence }) {
  if (confidence === 'unknown' || !CONFIDENCE_LABEL[confidence]) return null
  const tone = CONFIDENCE_TONE[confidence] || CONFIDENCE_TONE.none
  return (
    <span
      data-testid={`hq-confidence-chip-${confidence}`}
      className={`pill border ${tone}`}
    >
      {CONFIDENCE_LABEL[confidence]}
    </span>
  )
}

function ConfidenceBanner({ confidence }) {
  const text = BANNER_TEXT[confidence]
  if (!text) return null
  return (
    <div
      data-testid={`hq-confidence-banner-${confidence}`}
      className="mt-3 rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs text-amber-800"
    >
      {text}
    </div>
  )
}

function Disclaimer({ warnings }) {
  const codes = Array.isArray(warnings) ? warnings : []
  const required = ['QUALITY_SCORE_IS_DIAGNOSTIC_ONLY', 'NOT_A_BUSINESS_PERFORMANCE_SCORE']
  const merged = Array.from(new Set([...required, ...codes]))
  return (
    <div
      data-testid="historical-quality-disclaimer"
      className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs text-amber-800"
    >
      <p className="font-semibold">免责声明</p>
      <ul className="mt-1 list-disc space-y-0.5 pl-5">
        {merged.map((code) => (
          <li key={code} data-testid={`hq-warning-${code}`}>{warningText(code)}</li>
        ))}
      </ul>
    </div>
  )
}

function RadarChart({ dimensions }) {
  const option = useMemo(() => {
    const visibleKeys = DIMENSION_ORDER.filter((key) => {
      const node = dimensions?.[key]
      return !(node && typeof node === 'object' && node.not_applicable === true)
    })
    const indicators = visibleKeys.map((key) => ({ name: DIMENSION_LABEL[key], max: 1 }))
    const values = visibleKeys.map((key) => {
      const node = dimensions?.[key]
      const score = node && typeof node === 'object' ? node.score : node
      const num = Number(score)
      return Number.isFinite(num) ? Math.max(0, Math.min(1, num)) : 0
    })
    return {
      tooltip: { trigger: 'item' },
      radar: {
        indicator: indicators,
        radius: '65%',
        splitNumber: 4,
        axisName: { color: '#475569', fontSize: 11 },
        splitLine: { lineStyle: { color: '#e2e8f0' } },
        splitArea: { areaStyle: { color: ['#f8fafc', '#ffffff'] } }
      },
      series: [
        {
          type: 'radar',
          symbolSize: 6,
          areaStyle: { color: 'rgba(30, 64, 175, 0.18)' },
          lineStyle: { color: '#1e40af', width: 2 },
          itemStyle: { color: '#1e40af' },
          data: [{ value: values, name: '维度评分' }]
        }
      ]
    }
  }, [dimensions])

  const containerRef = useEcharts(option, [option])
  return <div ref={containerRef} data-testid="historical-quality-radar" className="h-64 w-full" />
}

function DimensionList({ dimensions }) {
  const entries = DIMENSION_ORDER
    .map((key) => {
      const node = dimensions?.[key]
      if (!node) return null
      const notApplicable = typeof node === 'object' && node.not_applicable === true
      const score = typeof node === 'object' ? node.score : node
      const num = Number(score)
      return {
        key,
        score: Number.isFinite(num) ? num : null,
        notApplicable,
        raw: node
      }
    })
    .filter(Boolean)
  if (!entries.length) return null
  return (
    <ul data-testid="historical-quality-dimensions" className="grid grid-cols-1 gap-2 sm:grid-cols-2">
      {entries.map(({ key, score, notApplicable }) => (
        <li
          key={key}
          data-testid={`hq-dim-${key}`}
          className="flex items-center justify-between rounded-lg border border-canvas-border bg-canvas-base px-3 py-2 text-sm"
        >
          <span className="text-slate-600">{DIMENSION_LABEL[key]}</span>
          {notApplicable ? (
            <span className="font-numeric text-slate-400">
              — <span className="ml-1 text-xs text-slate-400">(不适用)</span>
            </span>
          ) : (
            <span className="font-numeric text-slate-800">
              {score === null ? '—' : `${(score * 100).toFixed(0)}%`}
            </span>
          )}
        </li>
      ))}
    </ul>
  )
}

function PenaltyList({ penalties }) {
  const items = Array.isArray(penalties) ? penalties.filter(Boolean) : []
  if (!items.length) return null
  return (
    <div data-testid="historical-quality-penalties" className="rounded-xl border border-canvas-border bg-canvas-base p-3 text-sm">
      <p className="field-label">扣分项</p>
      <ul className="mt-2 space-y-1 text-slate-600">
        {items.map((item, idx) => {
          const reason = typeof item === 'object' ? item.reason || item.code : item
          const amount = typeof item === 'object' && Number.isFinite(Number(item.amount)) ? Number(item.amount) : null
          return (
            <li key={`${reason}-${idx}`} className="flex items-start justify-between gap-2">
              <span>{reasonText(reason) || String(reason)}</span>
              {amount !== null && <span className="font-numeric text-rose-600">-{(amount * 100).toFixed(0)}%</span>}
            </li>
          )
        })}
      </ul>
    </div>
  )
}

function HistoricalQualityCard({ quality }) {
  if (!quality || typeof quality !== 'object') return null

  const level = quality.level
  const scoreAvailable = quality.score_available === true
  const warnings = Array.isArray(quality.warnings) ? quality.warnings : []
  const confidence = deriveConfidence(quality)

  if (!scoreAvailable) {
    return (
      <div
        data-testid="historical-quality-card"
        className="md:col-span-2 rounded-2xl border border-canvas-border bg-canvas-base p-5"
      >
        <div className="flex items-start justify-between gap-3">
          <div>
            <p className="field-label">数据质量与历史可比性评分</p>
            <p className="mt-1 text-sm text-slate-500">
              本评分用于评估"当前仿真报告与历史样本的可比性",非业务表现指标。
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <ConfidenceChip confidence={confidence} />
            <span
              data-testid="historical-quality-level"
              className={`pill border ${levelClass(level)}`}
            >
              {levelLabel(level)}
            </span>
          </div>
        </div>
        <ConfidenceBanner confidence={confidence} />
        <div className="mt-4 rounded-xl border border-dashed border-slate-300 bg-slate-50 p-3 text-sm text-slate-600">
          <p className="font-semibold text-slate-700">当前不可评分</p>
          {quality.unavailable_reason && (
            <p data-testid="historical-quality-unavailable-reason" className="mt-1">
              原因:{reasonText(quality.unavailable_reason)}
            </p>
          )}
        </div>
        <div className="mt-4">
          <Disclaimer warnings={warnings} />
        </div>
      </div>
    )
  }

  const percentRaw = Number(quality.quality_score_percent)
  const percent = Number.isFinite(percentRaw) ? percentRaw : null

  return (
    <div
      data-testid="historical-quality-card"
      className="md:col-span-2 rounded-2xl border border-canvas-border bg-canvas-base p-5"
    >
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="field-label">数据质量与历史可比性评分</p>
          <p className="mt-1 text-sm text-slate-500">
            本评分用于评估"当前仿真报告与历史样本的可比性",非业务表现指标。
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <ConfidenceChip confidence={confidence} />
          <span
            data-testid="historical-quality-level"
            className={`pill border ${levelClass(level)}`}
          >
            {levelLabel(level)}
          </span>
        </div>
      </div>
      <ConfidenceBanner confidence={confidence} />

      <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
        <div className="flex flex-col gap-3">
          <div className="rounded-xl border border-canvas-border bg-canvas-base p-4">
            <p className="field-label">综合评分</p>
            <p className="mt-2 font-numeric text-4xl text-bjtu-700">
              {percent === null ? '—' : (
                <>
                  <span data-testid="historical-quality-percent">{percent.toFixed(1)}</span>
                  <span className="text-base text-slate-500"> / 100</span>
                </>
              )}
            </p>
          </div>
          <DimensionList dimensions={quality.dimensions} />
          <PenaltyList penalties={quality.penalties} />
        </div>
        <div>
          <RadarChart dimensions={quality.dimensions} />
        </div>
      </div>

      <div className="mt-4">
        <Disclaimer warnings={warnings} />
      </div>
    </div>
  )
}

export default HistoricalQualityCard
