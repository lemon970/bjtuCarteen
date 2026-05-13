import { API_BASE, HISTORY_PAGE_SIZE } from '../constants'

// [重构] 把所有后端请求集中在 API 层，页面组件不直接拼接接口地址，便于前后端契约统一维护。
async function requestJson(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, options)
  const text = await response.text()
  let body
  try {
    body = text ? JSON.parse(text) : {}
  } catch {
    throw new Error(`后端返回了非 JSON 内容（HTTP ${response.status}），请确认服务接口正常运行`)
  }
  if (!response.ok || body.code !== 0) {
    throw new Error(body.message || `HTTP ${response.status}`)
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

export function loadLatestReport() {
  return requestJson('/report/latest')
}

export function loadReportHistory(reportId, page = 1) {
  return requestJson(`/report/${reportId}/history?page=${page}&page_size=${HISTORY_PAGE_SIZE}`)
}

export function csvExportUrl(reportId) {
  return `${API_BASE}/report/${reportId}/csv`
}
