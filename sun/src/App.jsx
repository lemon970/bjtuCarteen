import { useEffect, useMemo, useState } from 'react'

import {
  getReportById,
  getTaskStatus,
  loadLatestReport,
  loadScenarioCatalog,
  runScenarioBatch,
  runSimulation,
  runSimulationAsync
} from './api/simulationApi'
import AppLayout from './components/AppLayout'
import {
  ASYNC_HARD_TIMEOUT_MS,
  ASYNC_POLL_INTERVALS_MS,
  ASYNC_POLL_MAX_CONSECUTIVE_ERRORS,
  DEFAULT_FORM
} from './constants'
import AnalysisPage from './pages/AnalysisPage'
import DisplayPage from './pages/DisplayPage'
import InputPage from './pages/InputPage'
import { decideRunMode } from './utils/asyncRunDecision'
import { applyPayloadToForm, buildPayload, read } from './utils/simulation'
import { useTaskPolling } from './utils/useTaskPolling'

function currentHashPage() {
  const key = window.location.hash.replace('#/', '') || 'input'
  return ['input', 'display', 'analysis'].includes(key) ? key : 'input'
}

function App() {
  const [activePage, setActivePage] = useState(currentHashPage)
  const [form, setForm] = useState(DEFAULT_FORM)
  const [report, setReport] = useState(null)
  const [scenarioCatalog, setScenarioCatalog] = useState(null)
  const [selectedScenarioIds, setSelectedScenarioIds] = useState(['lunch_peak_pressure'])
  const [scenarioResults, setScenarioResults] = useState([])
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState('')
  const [runMode, setRunMode] = useState('auto')
  const [activeTaskId, setActiveTaskId] = useState(null)

  const payload = useMemo(() => buildPayload(form), [form])
  const reportId = read(report, 'report_id', 'reportId') || ''
  const snapshotCount = Array.isArray(report?.summary?.timeline) ? report.summary.timeline.length : 0

  useEffect(() => {
    loadScenarioCatalog()
      .then((data) => {
        const scenarios = data?.scenarios || []
        setScenarioCatalog(scenarios)
        const peak = scenarios.find((item) => item.id === 'lunch_peak_pressure')
        if (peak?.config) {
          setForm(applyPayloadToForm(peak.config))
        }
      })
      .catch((error) => {
        setScenarioCatalog([])
        setMessage(`场景模型读取失败：${error.message}`)
      })
  }, [])

  const navigate = (page) => {
    window.location.hash = `/${page}`
    setActivePage(page)
  }

  const setField = (field, value) => {
    setForm((prev) => field === 'arrivalRate'
      ? { ...prev, arrivalRate: value, arrivalLambda: value }
      : { ...prev, [field]: value })
  }

  const handleLoadScenario = (scenarioId) => {
    const scenario = (scenarioCatalog || []).find((item) => item.id === scenarioId)
    if (!scenario?.config) {
      return
    }
    setSelectedScenarioIds([scenarioId])
    setForm(applyPayloadToForm(scenario.config))
    setMessage(`已加载模型：${scenario.name}`)
  }

  const handleToggleScenario = (scenarioId) => {
    setSelectedScenarioIds((prev) => (
      prev.includes(scenarioId)
        ? prev.filter((id) => id !== scenarioId)
        : [...prev, scenarioId]
    ))
  }

  const handleRun = async (event) => {
    event.preventDefault()
    const mode = decideRunMode(form, runMode)
    setLoading(true)
    setMessage('')
    setScenarioResults([])
    setActiveTaskId(null)

    if (mode === 'sync') {
      try {
        const data = await runSimulation(payload)
        setReport(data)
        setForm(applyPayloadToForm(data.config || payload))
        setMessage(`仿真完成，报告编号：${read(data, 'report_id', 'reportId')}`)
        navigate('display')
      } catch (error) {
        setMessage(`仿真失败：${error.message}`)
      } finally {
        setLoading(false)
      }
      return
    }

    try {
      const snapshot = await runSimulationAsync(payload)
      setActiveTaskId(read(snapshot, 'task_id', 'taskId'))
      setMessage('已提交仿真任务，正在等待后端执行…')
    } catch (error) {
      setMessage(`仿真提交失败：${error.message}`)
      setLoading(false)
    }
  }

  const handleAsyncTerminal = async (snapshot) => {
    const reportIdFromTask = read(snapshot, 'report_id', 'reportId')
    if (snapshot.status === 'FAILED') {
      setMessage(`仿真失败：${snapshot.error_message || '后端未返回错误信息'}`)
      setActiveTaskId(null)
      setLoading(false)
      return
    }
    if (snapshot.status !== 'COMPLETED' || !snapshot.report_available || !reportIdFromTask) {
      setMessage('仿真任务结束但报告不可用')
      setActiveTaskId(null)
      setLoading(false)
      return
    }
    try {
      const data = await getReportById(reportIdFromTask)
      setReport(data)
      setForm(applyPayloadToForm(data.config || payload))
      setMessage(`仿真完成，报告编号：${reportIdFromTask}`)
      navigate('display')
    } catch (error) {
      setMessage(`报告读取失败：${error.message}`)
    } finally {
      setActiveTaskId(null)
      setLoading(false)
    }
  }

  const handleAsyncError = (info) => {
    if (info?.reason === 'timeout') {
      setMessage(`仿真等待超时（10 分钟），后端任务可能仍在执行。task_id：${activeTaskId || '未知'}`)
    } else {
      setMessage('仿真状态轮询连续失败，已停止刷新。')
    }
    setActiveTaskId(null)
    setLoading(false)
  }

  useTaskPolling({
    taskId: activeTaskId,
    fetcher: getTaskStatus,
    intervals: ASYNC_POLL_INTERVALS_MS,
    hardTimeoutMs: ASYNC_HARD_TIMEOUT_MS,
    maxConsecutiveErrors: ASYNC_POLL_MAX_CONSECUTIVE_ERRORS,
    onTerminal: handleAsyncTerminal,
    onError: handleAsyncError
  })

  const handleRunScenarioBatch = async () => {
    const ids = selectedScenarioIds.length ? selectedScenarioIds : (scenarioCatalog || []).map((item) => item.id)
    setLoading(true)
    setMessage('')
    try {
      const data = await runScenarioBatch(ids)
      const results = data?.results || []
      setScenarioResults(results)
      if (results[0]) {
        setReport({
          report_id: results[0].report_id,
          config: results[0].config,
          summary: results[0].summary
        })
      }
      setMessage(`已完成 ${results.length} 个模型批量运行`)
      navigate('display')
    } catch (error) {
      setMessage(`批量运行失败：${error.message}`)
    } finally {
      setLoading(false)
    }
  }

  const handleLoadLatest = async () => {
    setLoading(true)
    setMessage('')
    try {
      const data = await loadLatestReport()
      setReport(data)
      setScenarioResults([])
      if (data.config) {
        setForm(applyPayloadToForm(data.config))
      }
      setMessage(`已读取最新报告：${read(data, 'report_id', 'reportId')}`)
      if (activePage === 'input') {
        navigate('display')
      }
    } catch (error) {
      setMessage(`读取失败：${error.message}`)
    } finally {
      setLoading(false)
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
      setMessage(`已导入配置文件：${file.name}`)
    } catch (error) {
      setMessage(`配置文件解析失败：${error.message}`)
    }
  }

  return (
    <AppLayout activePage={activePage} onNavigate={navigate} reportId={reportId} snapshotCount={snapshotCount}>
      {activePage === 'input' && (
        <InputPage
          form={form}
          loading={loading}
          message={message}
          scenarios={scenarioCatalog}
          selectedScenarioIds={selectedScenarioIds}
          onFieldChange={setField}
          onLoadScenario={handleLoadScenario}
          onToggleScenario={handleToggleScenario}
          onRunScenarioBatch={handleRunScenarioBatch}
          onReset={() => setForm(DEFAULT_FORM)}
          onRun={handleRun}
          onLoadLatest={handleLoadLatest}
          onFileUpload={handleFileUpload}
          runMode={runMode}
          onRunModeChange={setRunMode}
        />
      )}

      {activePage === 'display' && (
        <DisplayPage
          report={report}
          scenarioResults={scenarioResults}
          onLoadLatest={handleLoadLatest}
        />
      )}

      {activePage === 'analysis' && (
        <AnalysisPage report={report} scenarioResults={scenarioResults} payload={payload} onLoadLatest={handleLoadLatest} />
      )}
    </AppLayout>
  )
}

export default App
