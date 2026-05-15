import { useMemo, useState } from 'react'

import { formatNumber, formatPercent, read } from '../utils/simulation'

function ScenarioCompareTabs({ results = [] }) {
  const ids = useMemo(() => results.map((r) => r.scenario_id), [results])
  const [primary, setPrimary] = useState(ids[0])
  const [secondary, setSecondary] = useState(ids[1] || ids[0])

  if (results.length < 2) {
    return null
  }

  const left = results.find((r) => r.scenario_id === primary) || results[0]
  const right = results.find((r) => r.scenario_id === secondary) || results[1]

  const rows = [
    ['typical_wait_time_minutes', 'typicalWaitTimeMinutes', '典型等待 / 分钟'],
    ['seat_utilization_rate', 'seatUtilizationRate', '座位利用率', 'percent'],
    ['takeaway_rate', 'takeawayRate', '打包率', 'percent'],
    ['arrived_count', 'arrivedCount', '到达人数'],
    ['max_total_queue_size', 'maxTotalQueueSize', '峰值排队']
  ]

  return (
    <section className="panel">
      <div className="panel-title">
        <div>
          <h2>场景对比</h2>
          <p>选两个场景做指标差分,识别策略变更带来的影响。</p>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 mb-4">
        <ScenarioPicker label="基准场景" value={primary} options={results} onChange={setPrimary} accent="bjtu" />
        <ScenarioPicker label="对照场景" value={secondary} options={results} onChange={setSecondary} accent="amber" />
      </div>

      <div className="overflow-hidden rounded-xl border border-canvas-border">
        <table className="table-base">
          <thead>
            <tr>
              <th>指标</th>
              <th className="text-right">{left?.scenario_name}</th>
              <th className="text-right">{right?.scenario_name}</th>
              <th className="text-right">差值</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(([snake, camel, label, kind]) => {
              const a = read(left?.summary || {}, snake, camel) ?? 0
              const b = read(right?.summary || {}, snake, camel) ?? 0
              const delta = b - a
              const fmt = kind === 'percent' ? formatPercent : (v) => formatNumber(v, 2)
              const deltaClass = Math.abs(delta) < 1e-6
                ? 'text-slate-500'
                : delta > 0
                  ? 'text-semantic-warning'
                  : 'text-semantic-success'
              return (
                <tr key={snake}>
                  <td>{label}</td>
                  <td className="text-right font-numeric tabular-nums">{fmt(a)}</td>
                  <td className="text-right font-numeric tabular-nums">{fmt(b)}</td>
                  <td className={`text-right font-numeric tabular-nums ${deltaClass}`}>
                    {kind === 'percent' ? formatPercent(delta) : formatNumber(delta, 2)}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </section>
  )
}

function ScenarioPicker({ label, value, options, onChange, accent }) {
  const accentClass = accent === 'amber'
    ? 'border-accent-amber text-accent-amber'
    : 'border-bjtu-600 text-bjtu-700'
  return (
    <label className="block">
      <span className="field-label">{label}</span>
      <select
        className={`mt-1 w-full rounded-xl border bg-canvas-surface px-3 py-2 text-sm ${accentClass}`}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      >
        {options.map((scenario) => (
          <option key={scenario.scenario_id} value={scenario.scenario_id}>
            {scenario.scenario_name}
          </option>
        ))}
      </select>
    </label>
  )
}

export default ScenarioCompareTabs
