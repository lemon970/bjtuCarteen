import { useEffect, useMemo, useState } from 'react'

import { loadLatestReport, loadReportHistory, loadScenarioCatalog, runScenarioBatch, runSimulation } from './api/simulationApi'
import AppLayout from './components/AppLayout'
import { DEFAULT_FORM } from './constants'
import AnalysisPage from './pages/AnalysisPage'
import DisplayPage from './pages/DisplayPage'
import InputPage from './pages/InputPage'
import { applyPayloadToForm, buildPayload, read } from './utils/simulation'

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
  const [historyPage, setHistoryPage] = useState(null)
  const [loading, setLoading] = useState(false)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [message, setMessage] = useState('')

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
    setLoading(true)
    setMessage('')
    setHistoryPage(null)
    setScenarioResults([])
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
  }

  const handleRunScenarioBatch = async () => {
    const ids = selectedScenarioIds.length ? selectedScenarioIds : (scenarioCatalog || []).map((item) => item.id)
    setLoading(true)
    setMessage('')
    setHistoryPage(null)
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
    setHistoryPage(null)
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

  const handleLoadHistory = async (page = 1) => {
    if (!reportId) {
      return
    }
    setHistoryLoading(true)
    try {
      const data = await loadReportHistory(reportId, page)
      setHistoryPage(data)
    } catch (error) {
      setMessage(`历史快照读取失败：${error.message}`)
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
        />
      )}

      {activePage === 'display' && (
        <DisplayPage
          report={report}
          scenarioResults={scenarioResults}
          historyPage={historyPage}
          historyLoading={historyLoading}
          onLoadHistory={handleLoadHistory}
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
