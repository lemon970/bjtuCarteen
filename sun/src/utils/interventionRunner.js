import { INTERVENTIONS, applyFidelity } from './interventions'

export async function runInterventions({
  baselineForm,
  fidelityKey,
  interventionKeys,
  runFn,
  onProgress
}) {
  const scaledBaseline = applyFidelity(baselineForm, fidelityKey)
  const total = interventionKeys.length + 1
  let index = 0

  onProgress?.({ stage: 'baseline', index: ++index, total })
  let baseline
  try {
    baseline = { status: 'ok', report: await runFn(scaledBaseline) }
  } catch (err) {
    return {
      baseline: { status: 'error', error: err?.message ?? String(err) },
      interventions: []
    }
  }

  const interventions = []
  for (const key of interventionKeys) {
    onProgress?.({ stage: key, index: ++index, total })
    const def = INTERVENTIONS[key]
    if (!def) {
      interventions.push({ key, status: 'error', error: `未知干预 ${key}` })
      continue
    }
    const mutated = def.apply(scaledBaseline)
    try {
      interventions.push({ key, status: 'ok', report: await runFn(mutated) })
    } catch (err) {
      interventions.push({ key, status: 'error', error: err?.message ?? String(err) })
    }
  }
  return { baseline, interventions }
}
