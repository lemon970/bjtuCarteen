import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

import { runSimulationAsync, getTaskStatus, getReportById } from './simulationApi'

function jsonResponse(body, init = {}) {
  return {
    ok: init.ok ?? true,
    status: init.status ?? 200,
    text: () => Promise.resolve(JSON.stringify(body))
  }
}

describe('simulationApi async additions', () => {
  let fetchSpy

  beforeEach(() => {
    fetchSpy = vi.fn()
    globalThis.fetch = fetchSpy
  })

  afterEach(() => {
    delete globalThis.fetch
  })

  describe('runSimulationAsync', () => {
    it('POST 到 /api/simulation/run/async,带 JSON body 与 application/json header', async () => {
      const snapshot = {
        task_id: 't-1',
        report_id: 'r-1',
        status: 'PENDING',
        report_available: false,
        submitted_at_epoch_millis: 1,
        started_at_epoch_millis: 0,
        completed_at_epoch_millis: 0,
        error_message: ''
      }
      fetchSpy.mockResolvedValueOnce(jsonResponse({ code: 0, data: snapshot }, { status: 202 }))

      const payload = { duration: 2, arrival_rate: 300 }
      const data = await runSimulationAsync(payload)

      expect(fetchSpy).toHaveBeenCalledTimes(1)
      const [url, options] = fetchSpy.mock.calls[0]
      expect(url).toBe('/api/simulation/run/async')
      expect(options.method).toBe('POST')
      expect(options.headers['Content-Type']).toBe('application/json')
      expect(JSON.parse(options.body)).toEqual(payload)
      expect(data).toEqual(snapshot)
    })

    it('非 0 code 抛 ApiError', async () => {
      fetchSpy.mockResolvedValueOnce(jsonResponse({ code: 500, message: 'boom' }))
      await expect(runSimulationAsync({})).rejects.toThrow('boom')
    })
  })

  describe('getTaskStatus', () => {
    it('GET 到 /api/simulation/task/{id}/status', async () => {
      const snapshot = {
        task_id: 't-9',
        report_id: 'r-9',
        status: 'RUNNING',
        report_available: false,
        submitted_at_epoch_millis: 1,
        started_at_epoch_millis: 2,
        completed_at_epoch_millis: 0,
        error_message: ''
      }
      fetchSpy.mockResolvedValueOnce(jsonResponse({ code: 0, data: snapshot }))

      const data = await getTaskStatus('t-9')

      expect(fetchSpy).toHaveBeenCalledTimes(1)
      const [url, options] = fetchSpy.mock.calls[0]
      expect(url).toBe('/api/simulation/task/t-9/status')
      expect(options?.method ?? 'GET').toBe('GET')
      expect(data).toEqual(snapshot)
    })

    it('404 + body code=404 抛 ApiError(message=task not found)', async () => {
      fetchSpy.mockResolvedValueOnce(
        jsonResponse({ code: 404, message: 'task not found' }, { ok: false, status: 404 })
      )
      await expect(getTaskStatus('missing')).rejects.toThrow('task not found')
    })
  })

  describe('getReportById', () => {
    it('GET 到 /api/simulation/report/{id},URL 不带 include_history', async () => {
      const report = { config: {}, summary: { timeline: [] }, report_id: 'r-7' }
      fetchSpy.mockResolvedValueOnce(jsonResponse({ code: 0, data: report }))

      const data = await getReportById('r-7')

      expect(fetchSpy).toHaveBeenCalledTimes(1)
      const [url] = fetchSpy.mock.calls[0]
      expect(url).toBe('/api/simulation/report/r-7')
      expect(url).not.toMatch(/include_history/)
      expect(data).toEqual(report)
    })
  })
})
