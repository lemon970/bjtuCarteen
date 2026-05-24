export const MIN_DURATION_HOURS = 0.05

export function roundDuration(hours) {
  return Math.max(MIN_DURATION_HOURS, Math.round(hours * 100) / 100)
}

export const INTERVENTIONS = {
  ADD_NORMAL_WINDOW: {
    label: '+1 普通窗口',
    description: '增加普通窗口加快服务，缓解窗口饱和。',
    apply: (form) => ({ ...form, windowCount: form.windowCount + 1 }),
    enabledIf: (form) => form.windowCount < 20,
    summary: (form) => `windowCount: ${form.windowCount} → ${form.windowCount + 1}`,
    primaryFor: ['window_service_capacity']
  },
  ADD_TAKEAWAY_WINDOW: {
    label: '+1 打包窗口',
    description: '增加打包窗口分流外带学生，缓解打包队列压力。',
    apply: (form) => ({ ...form, takeawayWindowCount: form.takeawayWindowCount + 1 }),
    enabledIf: (form) => form.takeawayWindowCount < 5,
    summary: (form) => `takeawayWindowCount: ${form.takeawayWindowCount} → ${form.takeawayWindowCount + 1}`,
    primaryFor: ['takeaway_capacity']
  },
  ADD_SEATS: {
    label: '+50 座位',
    description: '增加座位减少无座强制打包和拼桌。',
    apply: (form) => ({ ...form, totalSeats: Math.min(form.totalSeats + 50, 1000) }),
    enabledIf: (form) => form.totalSeats < 1000,
    summary: (form) => `totalSeats: ${form.totalSeats} → ${Math.min(form.totalSeats + 50, 1000)}`,
    primaryFor: ['seat_capacity']
  },
  REDUCE_ARRIVAL: {
    label: '到达率 -10%',
    description: '到达冲击场景下，通过课表错峰降低到达强度。',
    apply: (form) => ({ ...form, arrivalRate: Math.round(form.arrivalRate * 0.9) }),
    enabledIf: (form) => form.arrivalRate >= 50,
    summary: (form) => `arrivalRate: ${form.arrivalRate} → ${Math.round(form.arrivalRate * 0.9)}`,
    primaryFor: ['arrival_surge']
  }
}

export function normalizeBottleneckPrimary(primary) {
  if (primary == null) return 'balanced'
  return String(primary).trim().toLowerCase().replaceAll('-', '_')
}

export const FIDELITY_PRESETS = {
  full:    { multiplier: 1.0,  label: '完整',  hint: '原 duration 完整跑' },
  preview: { multiplier: 0.5,  label: '预览',  hint: '半时长，可能错过峰值' },
  fast:    { multiplier: 0.25, label: '极速',  hint: '1/4 时长，峰值场景结论可能偏弱' }
}

export function applyFidelity(form, fidelityKey) {
  const preset = FIDELITY_PRESETS[fidelityKey] || FIDELITY_PRESETS.full
  const raw = Number(form.duration) * preset.multiplier
  return { ...form, duration: roundDuration(raw) }
}
