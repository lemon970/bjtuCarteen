import { INTERVENTIONS } from '../utils/interventions'

function InterventionPickerCard({ interventionKey, form, checked, onChange }) {
  const def = INTERVENTIONS[interventionKey]
  if (!def) return null
  const enabled = def.enabledIf(form)
  const summaryText = def.summary(form)
  const disableReason = !enabled ? '已达上限或参数不满足前置条件' : ''

  return (
    <label
      data-testid={`intervention-card-${interventionKey}`}
      className={`block cursor-pointer rounded-xl border p-3 transition ${
        !enabled
          ? 'cursor-not-allowed opacity-50'
          : checked
            ? 'border-bjtu-500 bg-bjtu-50'
            : 'border-canvas-border bg-canvas-surface hover:border-bjtu-300'
      }`}
      title={disableReason || def.description}
    >
      <div className="flex items-center gap-2">
        <input
          type="checkbox"
          checked={checked}
          disabled={!enabled}
          onChange={(e) => onChange(interventionKey, e.target.checked)}
          className="h-4 w-4 accent-bjtu-500"
        />
        <span className="text-sm font-medium text-slate-700">{def.label}</span>
      </div>
      <p className="mt-1 text-xs text-slate-500">{def.description}</p>
      <p className="mt-1 font-numeric text-xs text-slate-400">{summaryText}</p>
    </label>
  )
}

export default InterventionPickerCard
