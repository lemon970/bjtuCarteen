import { computeDiff, classifyDirection } from '../utils/interventionDiff'
import { INTERVENTIONS } from '../utils/interventions'
import { formatNumber, formatPercent } from '../utils/simulation'

function formatValue(spec, value) {
  if (value == null) return '—'
  if (spec.isPct) return formatPercent(value, spec.precision ?? 1)
  return formatNumber(value, spec.precision ?? 1)
}

function colorFor(dir) {
  if (dir === 'better') return 'text-emerald-600'
  if (dir === 'worse') return 'text-rose-600'
  return 'text-slate-500'
}

function InterventionDiffTable({ baselineReport, interventionResults }) {
  if (!baselineReport?.summary) return null
  const baselineSummary = baselineReport.summary

  const okList = interventionResults.filter(r => r.status === 'ok')
  const errList = interventionResults.filter(r => r.status === 'error')

  const diffs = okList.map(r => ({
    key: r.key,
    label: INTERVENTIONS[r.key]?.label ?? r.key,
    rows: computeDiff(baselineSummary, r.report.summary)
  }))

  const baselineRows = computeDiff(baselineSummary, baselineSummary)

  return (
    <div className="space-y-3" data-testid="intervention-diff-table">
      <div className="overflow-x-auto">
        <table className="min-w-full text-sm">
          <thead>
            <tr className="border-b border-canvas-border">
              <th className="py-2 text-left text-slate-500">指标</th>
              <th className="py-2 text-right text-slate-500">Baseline</th>
              {diffs.map(d => (
                <th key={d.key} className="py-2 text-right text-slate-500">{d.label}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {baselineRows.map((row, i) => (
              <tr key={row.key} className="border-b border-canvas-border/40">
                <td className="py-2 text-slate-700">{row.label}</td>
                <td className="py-2 text-right font-numeric tabular-nums text-bjtu-700">
                  {formatValue(row, row.baseline)}
                </td>
                {diffs.map(d => {
                  const cell = d.rows[i]
                  if (cell.status === 'missing') {
                    return <td key={d.key} className="py-2 text-right text-slate-400">缺失</td>
                  }
                  const dir = classifyDirection(row, cell.pct)
                  return (
                    <td key={d.key} className={`py-2 text-right font-numeric tabular-nums ${colorFor(dir)}`}>
                      {formatValue(row, cell.intervention)}
                      <span className="ml-1 text-xs">
                        ({cell.pct > 0 ? '+' : ''}{(cell.pct * 100).toFixed(0)}%)
                      </span>
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {errList.length > 0 && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-xs text-rose-700">
          {errList.map(r => (
            <p key={r.key}>
              <strong>{INTERVENTIONS[r.key]?.label ?? r.key}</strong>：运行失败 — {r.error}
            </p>
          ))}
        </div>
      )}
    </div>
  )
}

export default InterventionDiffTable
