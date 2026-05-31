import { useEffect, useMemo, useState } from 'react'
import { read } from '../utils/simulation'
import { INTERVENTIONS, FIDELITY_PRESETS, normalizeBottleneckPrimary } from '../utils/interventions'
import { runInterventions } from '../utils/interventionRunner'
import { buildConclusion } from '../utils/interventionConclusion'
import InterventionPickerCard from './InterventionPickerCard'
import InterventionDiffTable from './InterventionDiffTable'

const ALL_KEYS = Object.keys(INTERVENTIONS)
const MAX_SELECTED = 2

function defaultPreselect(primary) {
  const norm = normalizeBottleneckPrimary(primary)
  if (norm === 'balanced') return []
  const found = ALL_KEYS.find(k => INTERVENTIONS[k].primaryFor.includes(norm))
  return found ? [found] : []
}

function isSeedMissing(seed) {
  if (seed == null) return true
  const n = Number(seed)
  if (!Number.isFinite(n)) return true
  return n === 0
}

function InterventionPanel({ form, report, runFn }) {
  const primaryRaw = read(report?.summary, 'bottleneck_diagnosis', 'bottleneckDiagnosis')?.primary
  const primary = normalizeBottleneckPrimary(primaryRaw)
  const isBalanced = primary === 'balanced'

  const [selectedKeys, setSelectedKeys] = useState(() => defaultPreselect(primary))
  const [fidelityKey, setFidelityKey] = useState('full')
  const [running, setRunning] = useState(false)
  const [progress, setProgress] = useState(null)
  const [batchResult, setBatchResult] = useState(null)

  const previewDisabled = Number(form?.duration) < 0.5

  useEffect(() => {
    setSelectedKeys(defaultPreselect(primary))
    setBatchResult(null)
  }, [primary])

  useEffect(() => {
    if (previewDisabled && fidelityKey === 'preview') {
      setFidelityKey('full')
    }
  }, [previewDisabled, fidelityKey])

  function toggleKey(key, checked) {
    setSelectedKeys(prev => {
      const next = prev.filter(k => k !== key)
      if (!checked) return next
      next.push(key)
      while (next.length > MAX_SELECTED) next.shift()
      return next
    })
  }

  async function handleStart() {
    if (running || selectedKeys.length === 0) return
    setRunning(true)
    setBatchResult(null)
    setProgress({ stage: 'baseline', index: 0, total: selectedKeys.length + 1 })
    const baselineForm = structuredClone(form)
    try {
      const result = await runInterventions({
        baselineForm,
        fidelityKey,
        interventionKeys: selectedKeys,
        runFn,
        onProgress: setProgress
      })
      setBatchResult(result)
    } finally {
      setRunning(false)
      setProgress(null)
    }
  }

  const conclusionText = useMemo(() => {
    if (!batchResult || batchResult.baseline.status !== 'ok') return ''
    return buildConclusion({
      baselineSummary: batchResult.baseline.report.summary,
      interventionResults: batchResult.interventions
    })
  }, [batchResult])

  const seedMissing = isSeedMissing(form?.seed)

  if (!report) return null

  return (
    <section className="panel" data-testid="intervention-panel">
      <div className="panel-title">
        <div>
          <h2>运营干预对照</h2>
          <p>根据当前瓶颈诊断自动预选 1–2 个干预，前端协调多次仿真得到 KPI 对比。</p>
        </div>
      </div>

      {isBalanced && !batchResult && (
        <div className="empty-state mb-3">系统均衡，可手动启用对照，选择想验证的方向。</div>
      )}

      {seedMissing && (
        <div className="mb-3 rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs text-amber-700">
          未固定随机种子，baseline 与干预方案使用不同随机流，对照结果随机性更高；建议在 InputPage 设置 seed 后再来对照。
        </div>
      )}

      <div className="mb-3 flex flex-wrap items-center gap-3">
        <span className="text-xs text-slate-500">对照精度：</span>
        {Object.entries(FIDELITY_PRESETS).map(([key, preset]) => {
          const disabled = running || (key === 'preview' && previewDisabled)
          const hint = key === 'preview' && previewDisabled
            ? `${preset.hint}（baseline duration < 0.5h，预览档已停用）`
            : preset.hint
          return (
            <label key={key} className="flex items-center gap-1 text-xs">
              <input
                type="radio"
                name="fidelity"
                value={key}
                checked={fidelityKey === key}
                onChange={() => setFidelityKey(key)}
                disabled={disabled}
                className="accent-bjtu-500"
                aria-label={preset.label}
              />
              <span title={hint}>{preset.label}</span>
            </label>
          )
        })}
      </div>

      <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4 mb-3">
        {ALL_KEYS.map(key => (
          <InterventionPickerCard
            key={key}
            interventionKey={key}
            form={form}
            checked={selectedKeys.includes(key)}
            onChange={toggleKey}
          />
        ))}
      </div>

      <div className="flex items-center gap-3 mb-3">
        <button
          type="button"
          className="btn-primary"
          onClick={handleStart}
          disabled={running || selectedKeys.length === 0}
        >
          {running ? '运行中…' : '开始对照'}
        </button>
        {progress && (
          <span className="text-xs text-slate-500">
            进度 {progress.index}/{progress.total} — {progress.stage === 'baseline' ? '重跑 baseline' : INTERVENTIONS[progress.stage]?.label}
          </span>
        )}
      </div>

      {batchResult?.baseline?.status === 'error' && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-xs text-rose-700">
          baseline 重跑失败：{batchResult.baseline.error}
        </div>
      )}

      {batchResult?.baseline?.status === 'ok' && (
        <>
          <InterventionDiffTable
            baselineReport={batchResult.baseline.report}
            interventionResults={batchResult.interventions}
          />
          {conclusionText && (
            <p className="mt-3 rounded-xl border border-bjtu-200 bg-bjtu-50 p-3 text-sm text-bjtu-700">
              {conclusionText}
            </p>
          )}
          <p className="mt-2 text-xs text-slate-400">
            基于单次仿真，实际系统因随机性会有波动，本工具用于结构性对照而非精确预测。预览档将 duration 与 lunch/dinner peak 窗口同比缩放，保持场景"时间形状"，与完整档方向可比但绝对量级会偏弱。
          </p>
        </>
      )}
    </section>
  )
}

export default InterventionPanel
