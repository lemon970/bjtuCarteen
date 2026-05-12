import { useEffect, useMemo, useState } from 'react'

const API_BASE = '/api/simulation'
const MAX_STUDENTS = 1000
const HISTORY_PAGE_SIZE = 20

const DEFAULT_FORM = {
  simulationName: 'canteen-simulation',
  duration: 1.0,
  arrivalRate: 180,
  queueLimit: 10,
  packProbability: 0.2,
  seed: 12345,
  windowCount: 5,
  takeawayWindowCount: 1,
  takeawayServiceTimeMultiplier: 1.15,
  totalSeats: 120,
  totalStudents: 1000,
  arrivalInterval: 0,
  serviceMin: 60,
  serviceMax: 180,
  diningMin: 600,
  diningMax: 1200,
  preferenceMin: 0.1,
  preferenceMax: 0.3,
  currentWeather: 'sunny',
  weatherImpactFactor: 1.0,
  groupArrivalProb: 0,
  partySize: 1,
  walkTimeMean: 0,
  congestionPenalty: 0
}

function toNumber(value, fallback = 0) {
  const n = Number(value)
  return Number.isFinite(n) ? n : fallback
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value))
}

function formatNumber(value, digits = 2) {
  const n = Number(value)
  return Number.isFinite(n) ? n.toFixed(digits) : '0.00'
}

function read(obj, ...keys) {
  for (const key of keys) {
    if (obj && obj[key] !== undefined && obj[key] !== null) {
      return obj[key]
    }
  }
  return undefined
}

function buildPayload(form) {
  const windowCount = Math.max(1, Math.floor(toNumber(form.windowCount, 1)))
  const takeawayWindowCount = Math.min(
    windowCount,
    Math.max(0, Math.floor(toNumber(form.takeawayWindowCount, 0)))
  )
  const serviceMin = Math.max(1, Math.floor(toNumber(form.serviceMin, 60)))
  const serviceMax = Math.max(1, Math.floor(toNumber(form.serviceMax, 180)))
  const diningMin = Math.max(1, Math.floor(toNumber(form.diningMin, 600)))
  const diningMax = Math.max(1, Math.floor(toNumber(form.diningMax, 1200)))
  const preferenceMin = clamp(toNumber(form.preferenceMin, 0.1), 0, 1)
  const preferenceMax = clamp(toNumber(form.preferenceMax, 0.3), 0, 1)

  const payload = {
    simulationName: String(form.simulationName || DEFAULT_FORM.simulationName),
    duration: Math.max(0.1, toNumber(form.duration, DEFAULT_FORM.duration)),
    arrivalRate: Math.max(0, toNumber(form.arrivalRate, DEFAULT_FORM.arrivalRate)),
    queueLimit: Math.max(0, Math.floor(toNumber(form.queueLimit, DEFAULT_FORM.queueLimit))),
    packProbability: clamp(toNumber(form.packProbability, DEFAULT_FORM.packProbability), 0, 1),
    groupArrivalProb: clamp(toNumber(form.groupArrivalProb, DEFAULT_FORM.groupArrivalProb), 0, 1),
    partySize: Math.max(1, Math.floor(toNumber(form.partySize, DEFAULT_FORM.partySize))),
    walkTimeMean: Math.max(0, toNumber(form.walkTimeMean, DEFAULT_FORM.walkTimeMean)),
    congestionPenalty: Math.max(0, toNumber(form.congestionPenalty, DEFAULT_FORM.congestionPenalty)),
    baseConfig: {
      windowCount,
      takeawayWindowCount,
      takeawayServiceTimeMultiplier: Math.max(
        1,
        toNumber(form.takeawayServiceTimeMultiplier, DEFAULT_FORM.takeawayServiceTimeMultiplier)
      ),
      totalSeats: Math.max(0, Math.floor(toNumber(form.totalSeats, DEFAULT_FORM.totalSeats))),
      totalStudents: Math.min(
        MAX_STUDENTS,
        Math.max(0, Math.floor(toNumber(form.totalStudents, DEFAULT_FORM.totalStudents)))
      )
    },
    weatherConfig: {
      currentWeather: form.currentWeather || DEFAULT_FORM.currentWeather,
      weatherImpactFactor: Math.max(0, toNumber(form.weatherImpactFactor, DEFAULT_FORM.weatherImpactFactor))
    },
    randomBounds: {
      arrivalInterval: Math.max(0, Math.floor(toNumber(form.arrivalInterval, DEFAULT_FORM.arrivalInterval))),
      serviceRange: [Math.min(serviceMin, serviceMax), Math.max(serviceMin, serviceMax)],
      diningRange: [Math.min(diningMin, diningMax), Math.max(diningMin, diningMax)],
      preferenceRange: [Math.min(preferenceMin, preferenceMax), Math.max(preferenceMin, preferenceMax)]
    }
  }

  const seed = toNumber(form.seed, NaN)
  if (Number.isFinite(seed)) {
    payload.seed = Math.trunc(seed)
  }
  return payload
}

function applyPayloadToForm(payload) {
  const base = read(payload, 'baseConfig', 'base_config') || {}
  const weather = read(payload, 'weatherConfig', 'weather_config') || {}
  const random = read(payload, 'randomBounds', 'random_bounds') || {}
  const serviceRange = read(random, 'serviceRange', 'service_range') || [
    DEFAULT_FORM.serviceMin,
    DEFAULT_FORM.serviceMax
  ]
  const diningRange = read(random, 'diningRange', 'dining_range') || [
    DEFAULT_FORM.diningMin,
    DEFAULT_FORM.diningMax
  ]
  const preferenceRange = read(random, 'preferenceRange', 'preference_range') || [
    DEFAULT_FORM.preferenceMin,
    DEFAULT_FORM.preferenceMax
  ]

  return {
    ...DEFAULT_FORM,
    simulationName: read(payload, 'simulationName', 'simulation_name') || DEFAULT_FORM.simulationName,
    duration: read(payload, 'duration', 'duration_hours', 'duration_hour') ?? DEFAULT_FORM.duration,
    arrivalRate: read(payload, 'arrivalRate', 'arrival_rate', 'arrival_rate_per_hour') ?? DEFAULT_FORM.arrivalRate,
    queueLimit: read(payload, 'queueLimit', 'queue_limit') ?? DEFAULT_FORM.queueLimit,
    packProbability: read(payload, 'packProbability', 'pack_probability') ?? DEFAULT_FORM.packProbability,
    seed: read(payload, 'seed') ?? DEFAULT_FORM.seed,
    groupArrivalProb: read(payload, 'groupArrivalProb', 'group_arrival_prob') ?? DEFAULT_FORM.groupArrivalProb,
    partySize: read(payload, 'partySize', 'party_size') ?? DEFAULT_FORM.partySize,
    walkTimeMean: read(payload, 'walkTimeMean', 'walk_time_mean') ?? DEFAULT_FORM.walkTimeMean,
    congestionPenalty: read(payload, 'congestionPenalty', 'congestion_penalty') ?? DEFAULT_FORM.congestionPenalty,
    windowCount: read(base, 'windowCount', 'window_count') ?? DEFAULT_FORM.windowCount,
    takeawayWindowCount: read(base, 'takeawayWindowCount', 'takeaway_window_count') ?? DEFAULT_FORM.takeawayWindowCount,
    takeawayServiceTimeMultiplier:
      read(base, 'takeawayServiceTimeMultiplier', 'takeaway_service_time_multiplier') ??
      DEFAULT_FORM.takeawayServiceTimeMultiplier,
    totalSeats: read(base, 'totalSeats', 'total_seats') ?? DEFAULT_FORM.totalSeats,
    totalStudents: read(base, 'totalStudents', 'total_students') ?? DEFAULT_FORM.totalStudents,
    currentWeather: read(weather, 'currentWeather', 'current_weather') ?? DEFAULT_FORM.currentWeather,
    weatherImpactFactor:
      read(weather, 'weatherImpactFactor', 'weather_impact_factor') ?? DEFAULT_FORM.weatherImpactFactor,
    arrivalInterval: read(random, 'arrivalInterval', 'arrival_interval') ?? DEFAULT_FORM.arrivalInterval,
    serviceMin: Array.isArray(serviceRange) ? serviceRange[0] : DEFAULT_FORM.serviceMin,
    serviceMax: Array.isArray(serviceRange) ? serviceRange[1] : DEFAULT_FORM.serviceMax,
    diningMin: Array.isArray(diningRange) ? diningRange[0] : DEFAULT_FORM.diningMin,
    diningMax: Array.isArray(diningRange) ? diningRange[1] : DEFAULT_FORM.diningMax,
    preferenceMin: Array.isArray(preferenceRange) ? preferenceRange[0] : DEFAULT_FORM.preferenceMin,
    preferenceMax: Array.isArray(preferenceRange) ? preferenceRange[1] : DEFAULT_FORM.preferenceMax
  }
}

function normalizePoint(point, index) {
  const timeSeconds = read(point, 'time_seconds', 'timeSeconds', 'time') ?? index * 60
  return {
    timeSeconds,
    label: Math.round(timeSeconds / 60),
    queue: read(point, 'total_queue_size', 'totalQueueSize', 'queueing_student_count', 'queueingStudentCount') ?? 0,
    seats: read(point, 'occupied_seats', 'occupiedSeats', 'dining_student_count', 'diningStudentCount') ?? 0,
    seatRate: read(point, 'seat_utilization_rate', 'seatUtilizationRate') ?? 0
  }
}

function MiniTimelineChart({ timeline }) {
  const chartData = useMemo(() => {
    if (!Array.isArray(timeline) || timeline.length === 0) {
      return []
    }
    return timeline.map(normalizePoint)
  }, [timeline])

  if (chartData.length === 0) {
    return <div className="empty-state">No timeline snapshot yet.</div>
  }

  const width = 720
  const height = 220
  const padding = 28
  const maxValue = Math.max(1, ...chartData.flatMap((item) => [item.queue, item.seats]))
  const xStep = chartData.length <= 1 ? 0 : (width - padding * 2) / (chartData.length - 1)

  const points = (key) =>
    chartData
      .map((item, index) => {
        const x = padding + index * xStep
        const y = height - padding - (item[key] / maxValue) * (height - padding * 2)
        return `${x},${y}`
      })
      .join(' ')

  return (
    <div className="chart-wrap">
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label="simulation trend chart">
        <line className="axis" x1={padding} y1={height - padding} x2={width - padding} y2={height - padding} />
        <line className="axis" x1={padding} y1={padding} x2={padding} y2={height - padding} />
        <polyline className="chart-line queue" points={points('queue')} />
        <polyline className="chart-line seats" points={points('seats')} />
      </svg>
      <div className="legend">
        <span><i className="queue-dot" />Queue size</span>
        <span><i className="seat-dot" />Occupied seats</span>
      </div>
    </div>
  )
}

function App() {
  const [form, setForm] = useState(DEFAULT_FORM)
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState('')
  const [report, setReport] = useState(null)
  const [historyPage, setHistoryPage] = useState(null)
  const [historyLoading, setHistoryLoading] = useState(false)

  const payload = useMemo(() => buildPayload(form), [form])
  const summary = report?.summary || {}
  const reportId = read(report, 'report_id', 'reportId') || ''
  const timeline = summary.timeline || []

  const setField = (field, value) => {
    setForm((prev) => ({ ...prev, [field]: value }))
  }

  const runSimulation = async (event) => {
    event.preventDefault()
    setLoading(true)
    setMessage('')
    setHistoryPage(null)
    try {
      const response = await fetch(`${API_BASE}/run`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      })
      const body = await response.json()
      if (!response.ok || body.code !== 0) {
        throw new Error(body.message || `HTTP ${response.status}`)
      }
      setReport(body.data)
      setForm(applyPayloadToForm(body.data.config || payload))
      setMessage(`Simulation completed. Report ID: ${read(body.data, 'report_id', 'reportId')}`)
    } catch (error) {
      setMessage(`Simulation failed: ${error.message}`)
    } finally {
      setLoading(false)
    }
  }

  const loadLatest = async () => {
    setLoading(true)
    setMessage('')
    setHistoryPage(null)
    try {
      const response = await fetch(`${API_BASE}/report/latest`)
      const body = await response.json()
      if (!response.ok || body.code !== 0) {
        throw new Error(body.message || `HTTP ${response.status}`)
      }
      setReport(body.data)
      if (body.data.config) {
        setForm(applyPayloadToForm(body.data.config))
      }
      setMessage(`Loaded latest report: ${read(body.data, 'report_id', 'reportId')}`)
    } catch (error) {
      setMessage(`Load failed: ${error.message}`)
    } finally {
      setLoading(false)
    }
  }

  const loadHistory = async (page = 1) => {
    if (!reportId) {
      return
    }
    setHistoryLoading(true)
    try {
      const response = await fetch(`${API_BASE}/report/${reportId}/history?page=${page}&page_size=${HISTORY_PAGE_SIZE}`)
      const body = await response.json()
      if (!response.ok || body.code !== 0) {
        throw new Error(body.message || `HTTP ${response.status}`)
      }
      setHistoryPage(body.data)
    } catch (error) {
      setMessage(`History load failed: ${error.message}`)
    } finally {
      setHistoryLoading(false)
    }
  }

  const handleFileUpload = async (event) => {
    const file = event.target.files?.[0]
    if (!file) {
      return
    }
    try {
      const text = await file.text()
      const json = JSON.parse(text)
      const config = json.data?.config || json.config || json
      setForm(applyPayloadToForm(config))
      setMessage(`Imported config file: ${file.name}`)
    } catch (error) {
      setMessage(`Config parse failed: ${error.message}`)
    }
  }

  return (
    <main className="app-shell">
      <section className="topbar">
        <div>
          <p className="eyebrow">BJTU Canteen Simulation</p>
          <h1>Simulation Control Console</h1>
        </div>
        <div className="top-actions">
          <button type="button" className="secondary" onClick={loadLatest} disabled={loading}>
            Load latest
          </button>
        </div>
      </section>

      <section className="workspace">
        <form className="control-panel" onSubmit={runSimulation}>
          <div className="panel-head">
            <h2>Input parameters</h2>
            <button type="button" className="ghost" onClick={() => setForm(DEFAULT_FORM)}>
              Reset
            </button>
          </div>

          <label>
            Simulation name
            <input value={form.simulationName} onChange={(e) => setField('simulationName', e.target.value)} />
          </label>

          <div className="grid-2">
            <label>
              Duration / hour
              <input type="number" min="0.1" step="0.1" value={form.duration} onChange={(e) => setField('duration', e.target.value)} />
            </label>
            <label>
              Arrival rate / hour
              <input type="number" min="0" step="1" value={form.arrivalRate} onChange={(e) => setField('arrivalRate', e.target.value)} />
            </label>
          </div>

          <div className="grid-2">
            <label>
              Windows
              <input type="number" min="1" step="1" value={form.windowCount} onChange={(e) => setField('windowCount', e.target.value)} />
            </label>
            <label>
              Takeaway windows
              <input type="number" min="0" step="1" value={form.takeawayWindowCount} onChange={(e) => setField('takeawayWindowCount', e.target.value)} />
            </label>
          </div>

          <div className="grid-2">
            <label>
              Seats
              <input type="number" min="0" step="1" value={form.totalSeats} onChange={(e) => setField('totalSeats', e.target.value)} />
            </label>
            <label>
              Students cap
              <input type="number" min="0" max={MAX_STUDENTS} step="1" value={form.totalStudents} onChange={(e) => setField('totalStudents', e.target.value)} />
            </label>
          </div>

          <div className="grid-2">
            <label>
              Queue limit
              <input type="number" min="0" step="1" value={form.queueLimit} onChange={(e) => setField('queueLimit', e.target.value)} />
            </label>
            <label>
              Takeaway probability
              <input type="number" min="0" max="1" step="0.01" value={form.packProbability} onChange={(e) => setField('packProbability', e.target.value)} />
            </label>
          </div>

          <div className="grid-2">
            <label>
              Service min / sec
              <input type="number" min="1" step="1" value={form.serviceMin} onChange={(e) => setField('serviceMin', e.target.value)} />
            </label>
            <label>
              Service max / sec
              <input type="number" min="1" step="1" value={form.serviceMax} onChange={(e) => setField('serviceMax', e.target.value)} />
            </label>
          </div>

          <div className="grid-2">
            <label>
              Dining min / sec
              <input type="number" min="1" step="1" value={form.diningMin} onChange={(e) => setField('diningMin', e.target.value)} />
            </label>
            <label>
              Dining max / sec
              <input type="number" min="1" step="1" value={form.diningMax} onChange={(e) => setField('diningMax', e.target.value)} />
            </label>
          </div>

          <div className="grid-2">
            <label>
              Weather
              <select value={form.currentWeather} onChange={(e) => setField('currentWeather', e.target.value)}>
                <option value="sunny">sunny</option>
                <option value="cloudy">cloudy</option>
                <option value="rainy">rainy</option>
              </select>
            </label>
            <label>
              Weather factor
              <input type="number" min="0" step="0.1" value={form.weatherImpactFactor} onChange={(e) => setField('weatherImpactFactor', e.target.value)} />
            </label>
          </div>

          <div className="grid-2">
            <label>
              Arrival interval / sec
              <input type="number" min="0" step="1" value={form.arrivalInterval} onChange={(e) => setField('arrivalInterval', e.target.value)} />
            </label>
            <label>
              Random seed
              <input type="number" step="1" value={form.seed} onChange={(e) => setField('seed', e.target.value)} />
            </label>
          </div>

          <label>
            Import JSON config
            <input type="file" accept=".json" onChange={handleFileUpload} />
          </label>

          <button className="primary" type="submit" disabled={loading}>
            {loading ? 'Running...' : 'Run simulation'}
          </button>
          {message && <div className="message">{message}</div>}
        </form>

        <section className="dashboard">
          <div className="kpi-grid">
            <Metric title="Arrived" value={read(summary, 'arrived_count', 'arrivedCount') ?? 0} />
            <Metric title="Served" value={read(summary, 'served_count', 'servedCount') ?? 0} />
            <Metric title="Abandoned" value={read(summary, 'abandoned_count', 'abandonedCount') ?? 0} />
            <Metric title="Avg wait" value={`${formatNumber(read(summary, 'avg_wait_time_minutes', 'avgWaitTimeMinutes') ?? 0)} min`} />
            <Metric title="Seat usage" value={`${formatNumber((read(summary, 'seat_utilization_rate', 'seatUtilizationRate') ?? 0) * 100, 1)}%`} />
            <Metric title="Takeaway" value={`${formatNumber((read(summary, 'takeaway_rate', 'takeawayRate') ?? 0) * 100, 1)}%`} />
          </div>

          <section className="panel">
            <div className="panel-head">
              <h2>Snapshot trend</h2>
              <span>{reportId || 'No report yet'}</span>
            </div>
            <MiniTimelineChart timeline={timeline} />
          </section>

          <ReplayPanel timeline={timeline} />

          <ReportPanel report={report} payload={payload} />

          <section className="panel">
            <div className="panel-head">
              <h2>Event snapshots</h2>
              <button type="button" className="secondary" onClick={() => loadHistory(1)} disabled={!reportId || historyLoading}>
                {historyLoading ? 'Loading...' : 'Load first page'}
              </button>
            </div>
            <HistoryTable page={historyPage} onPage={loadHistory} loading={historyLoading} />
          </section>

          <section className="panel payload-panel">
            <h2>Request payload</h2>
            <pre>{JSON.stringify(payload, null, 2)}</pre>
          </section>
        </section>
      </section>
    </main>
  )
}

function Metric({ title, value }) {
  return (
    <article className="metric-card">
      <span>{title}</span>
      <strong>{value}</strong>
    </article>
  )
}

function HistoryTable({ page, onPage, loading }) {
  const items = page?.items || []
  if (!page) {
    return <div className="empty-state">Run a simulation, then load paged event snapshots.</div>
  }
  return (
    <div>
      <div className="history-meta">
        <span>Page {page.page} / {page.total_pages || 0}</span>
        <span>Total {page.total_items || 0}</span>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Time / s</th>
              <th>Queue</th>
              <th>Seats</th>
              <th>Arrived</th>
              <th>Served</th>
              <th>Event</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item, index) => (
              <tr key={`${read(item, 'time_seconds', 'time') ?? index}-${index}`}>
                <td>{read(item, 'time_seconds', 'time') ?? 0}</td>
                <td>{read(item, 'total_queue_size', 'totalQueueSize') ?? 0}</td>
                <td>{read(item, 'occupied_seats', 'occupiedSeats') ?? 0}</td>
                <td>{read(item, 'arrived_count', 'arrivedCount') ?? 0}</td>
                <td>{read(item, 'served_count', 'servedCount') ?? 0}</td>
                <td>{read(item, 'event_message', 'eventMessage') || ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="pager">
        <button type="button" className="secondary" disabled={!page.has_previous || loading} onClick={() => onPage(page.page - 1)}>
          Previous
        </button>
        <button type="button" className="secondary" disabled={!page.has_next || loading} onClick={() => onPage(page.page + 1)}>
          Next
        </button>
      </div>
    </div>
  )
}

function ReplayPanel({ timeline }) {
  const [currentIndex, setCurrentIndex] = useState(0)
  const [playing, setPlaying] = useState(false)

  useEffect(() => {
    setCurrentIndex(0)
    setPlaying(false)
  }, [timeline])

  useEffect(() => {
    if (!playing || !Array.isArray(timeline) || timeline.length <= 1) {
      return undefined
    }
    const timer = window.setInterval(() => {
      setCurrentIndex((prev) => {
        if (prev >= timeline.length - 1) {
          setPlaying(false)
          return prev
        }
        return prev + 1
      })
    }, 180)
    return () => window.clearInterval(timer)
  }, [playing, timeline])

  const point = Array.isArray(timeline) ? timeline[currentIndex] : null
  const normalized = point ? normalizePoint(point, currentIndex) : null
  const queueSizes = read(point, 'window_queue_sizes', 'windowQueueSizes') || []
  const windowTypes = read(point, 'window_types', 'windowTypes') || []
  const maxQueue = Math.max(1, ...queueSizes)
  const seatRate = normalized ? normalized.seatRate * 100 : 0

  return (
    <section className="panel">
      <div className="panel-head">
        <h2>Replay board</h2>
        <button
          type="button"
          className="secondary"
          onClick={() => setPlaying((value) => !value)}
          disabled={!timeline?.length}
        >
          {playing ? 'Pause' : 'Play'}
        </button>
      </div>

      {!timeline?.length ? (
        <div className="empty-state">No replay data yet.</div>
      ) : (
        <>
          <div className="replay-controls">
            <span>{formatClock(normalized?.timeSeconds ?? 0)}</span>
            <input
              className="timeline-control"
              type="range"
              min="0"
              max={Math.max(0, timeline.length - 1)}
              value={currentIndex}
              onChange={(event) => setCurrentIndex(Number(event.target.value))}
            />
            <span>{currentIndex + 1} / {timeline.length}</span>
          </div>

          <div className="replay-grid">
            <Metric title="Current queue" value={normalized?.queue ?? 0} />
            <Metric title="Occupied seats" value={normalized?.seats ?? 0} />
            <Metric title="Seat usage" value={`${formatNumber(seatRate, 1)}%`} />
          </div>

          <div className="window-bars">
            {queueSizes.length === 0 ? (
              <div className="empty-state">This point has no window queue detail.</div>
            ) : (
              queueSizes.map((size, index) => {
                const type = windowTypes[index] || 'NORMAL'
                return (
                  <div className="window-row" key={`${index}-${type}`}>
                    <span>{type === 'TAKEAWAY' ? 'Takeaway' : `Window ${index + 1}`}</span>
                    <div className="bar-track">
                      <div
                        className={type === 'TAKEAWAY' ? 'bar-fill takeaway' : 'bar-fill'}
                        style={{ width: `${Math.max(3, (size / maxQueue) * 100)}%` }}
                      />
                    </div>
                    <strong>{size}</strong>
                  </div>
                )
              })
            )}
          </div>
        </>
      )}
    </section>
  )
}

function ReportPanel({ report, payload }) {
  const summary = report?.summary || {}
  const config = report?.config || payload
  const timeline = summary.timeline || []
  const points = Array.isArray(timeline) ? timeline.map(normalizePoint) : []
  const peak = points.reduce(
    (best, point) => (point.seatRate > best.seatRate ? point : best),
    { seatRate: 0, label: 0 }
  )
  const crowdedMinutes = points.filter((point) => point.seatRate >= 0.7).length
  const crowdedRatio = points.length === 0 ? 0 : (crowdedMinutes / points.length) * 100
  const base = config?.base_config || config?.baseConfig || {}

  return (
    <section className="panel stat-report">
      <div className="panel-head">
        <h2>Statistics report</h2>
        <span>{report ? 'From backend report' : 'Preview from current input'}</span>
      </div>
      <div className="report-grid">
        <div className="param-table">
          <div><span>Duration</span><strong>{read(config, 'duration') ?? '-'}</strong></div>
          <div><span>Arrival rate</span><strong>{read(config, 'arrival_rate', 'arrivalRate') ?? '-'}</strong></div>
          <div><span>Total seats</span><strong>{read(base, 'total_seats', 'totalSeats') ?? '-'}</strong></div>
          <div><span>Total students</span><strong>{read(base, 'total_students', 'totalStudents') ?? '-'}</strong></div>
          <div><span>Windows</span><strong>{read(base, 'window_count', 'windowCount') ?? '-'}</strong></div>
          <div><span>Takeaway windows</span><strong>{read(base, 'takeaway_window_count', 'takeawayWindowCount') ?? '-'}</strong></div>
        </div>
        <div className="report-metrics">
          <Metric title="Peak usage" value={`${formatNumber(peak.seatRate * 100, 1)}%`} />
          <Metric title="Peak minute" value={peak.label} />
          <Metric title="Crowded points" value={crowdedMinutes} />
          <Metric title="Crowded ratio" value={`${formatNumber(crowdedRatio, 1)}%`} />
        </div>
      </div>
    </section>
  )
}

function formatClock(seconds) {
  const safeSeconds = Math.max(0, Math.floor(toNumber(seconds, 0)))
  const minutes = Math.floor(safeSeconds / 60)
  const remainder = safeSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`
}

export default App
