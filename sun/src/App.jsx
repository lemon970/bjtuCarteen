import { useMemo, useState } from 'react'

import { loadLatestReport, loadReportHistory, runSimulation } from './api/simulationApi'
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

// [重构] App 只保留全局状态和页面路由，原因是原文件同时承担页面、组件、API 和适配逻辑，维护成本过高。
function App() {
  const [activePage, setActivePage] = useState(currentHashPage)
  const [form, setForm] = useState(DEFAULT_FORM)
  const [report, setReport] = useState(null)
  const [historyPage, setHistoryPage] = useState(null)
  const [loading, setLoading] = useState(false)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [message, setMessage] = useState('')

  const payload = useMemo(() => buildPayload(form), [form])
  const reportId = read(report, 'report_id', 'reportId') || ''

  const navigate = (page) => {
    window.location.hash = `/${page}`
    setActivePage(page)
  }

  const setField = (field, value) => {
    setForm((prev) => ({ ...prev, [field]: value }))
  }

  const handleRun = async (event) => {
    event.preventDefault()
    setLoading(true)
    setMessage('')
    setHistoryPage(null)
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

  const handleLoadLatest = async () => {
    setLoading(true)
    setMessage('')
    setHistoryPage(null)
    try {
      const data = await loadLatestReport()
      setReport(data)
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
    <AppLayout activePage={activePage} onNavigate={navigate} reportId={reportId}>
      {activePage === 'input' && (
        <InputPage
          form={form}
          loading={loading}
          message={message}
          onFieldChange={setField}
          onReset={() => setForm(DEFAULT_FORM)}
          onRun={handleRun}
          onLoadLatest={handleLoadLatest}
          onFileUpload={handleFileUpload}
        />
      )}

      {activePage === 'display' && (
        <DisplayPage
          report={report}
          historyPage={historyPage}
          historyLoading={historyLoading}
          onLoadHistory={handleLoadHistory}
          onLoadLatest={handleLoadLatest}
        />
      )}

      {activePage === 'analysis' && (
        <AnalysisPage report={report} payload={payload} onLoadLatest={handleLoadLatest} />
      )}
    </AppLayout>
  )
}

export default App
