import { API_BASE, HISTORY_PAGE_SIZE } from '../constants'

const ANALYSIS_BASE = '/api/analysis'

class ApiError extends Error {
  constructor(message, payload) {
    super(message)
    this.payload = payload
  }
}

async function requestJson(path, options = {}, base = API_BASE) {
  const response = await fetch(`${base}${path}`, options)
  const text = await response.text()
  let body
  try {
    body = text ? JSON.parse(text) : {}
  } catch {
    throw new ApiError(`后端返回了非 JSON 内容,HTTP ${response.status}`, { code: response.status })
  }
  if (!response.ok || body.code !== 0) {
    throw new ApiError(body.message || `HTTP ${response.status}`, body)
  }
  return body.data
}

export function runSimulation(payload) {
  return requestJson('/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

export function runSimulationAsync(payload) {
  return requestJson('/run/async', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

export function getTaskStatus(taskId) {
  return requestJson(`/task/${encodeURIComponent(taskId)}/status`)
}

export function getReportById(reportId) {
  return requestJson(`/report/${encodeURIComponent(reportId)}`)
}

export function loadScenarioCatalog() {
  return requestJson('/scenarios')
}

export function runScenarioBatch(scenarioIds, overrides) {
  return requestJson('/scenarios/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ scenario_ids: scenarioIds, overrides })
  })
}

export function loadLatestReport() {
  return requestJson('/report/latest')
}

export function loadReportHistory(reportId, page = 1) {
  return requestJson(`/report/${reportId}/history?page=${page}&page_size=${HISTORY_PAGE_SIZE}`)
}

export function csvExportUrl(reportId) {
  return `${API_BASE}/report/${reportId}/csv`
}

export function runAnalysis(reportId, options = {}) {
  const body = { reportId }
  if (options.includeHistoricalQuality === true) {
    body.include_historical_quality = true
  }
  if (options.includeHistoricalDiagnostics === true) {
    body.include_historical_diagnostics = true
  }
  return requestJson('/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  }, ANALYSIS_BASE)
}
