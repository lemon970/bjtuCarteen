import { useState } from 'react'

import { runAnalysis } from '../api/simulationApi'
import { formatNumber, read } from '../utils/simulation'

function AdvancedStatsPanel({ reportId }) {
  const [state, setState] = useState({ status: 'idle', data: null, error: '' })

  const onRun = async () => {
    if (!reportId) return
    setState({ status: 'loading', data: null, error: '' })
    try {
      const data = await runAnalysis(reportId)
      setState({ status: 'ok', data, error: '' })
    } catch (error) {
      const reason = error?.payload?.data?.reason || error?.payload?.data?.available === false
        ? error?.payload?.data?.reason
        : error?.message
      setState({
        status: error?.payload?.code === 503 ? 'unavailable' : 'error',
        data: error?.payload?.data || null,
        error: reason || error?.message || '高级分析调用失败'
      })
    }
  }

  return (
    <section className="panel">
      <div className="panel-title">
        <div>
          <h2>高级统计 (C++ 后处理)</h2>
          <p>
            把当前报告交给 <code className="text-bjtu-700">canteen-analyze</code> 二进制做 bootstrap 置信区间和瓶颈打分。
            未编译 C++ 时会优雅降级。
          </p>
        </div>
        <button type="button" className="btn-primary" disabled={!reportId || state.status === 'loading'} onClick={onRun}>
          {state.status === 'loading' ? '正在分析…' : '运行高级分析'}
        </button>
      </div>

      {state.status === 'idle' && (
        <div className="empty-state">
          {reportId ? '点击"运行高级分析"以生成置信区间与瓶颈分。' : '尚未生成报告,先去运行仿真。'}
        </div>
      )}

      {state.status === 'unavailable' && (
        <div className="rounded-xl border border-canvas-border bg-canvas-base p-4 text-sm">
          <p className="font-semibold text-slate-700">分析二进制不可用</p>
          <p className="mt-1 text-slate-500">
            原因:{state.error}。请按 dataAnalyze/README.md 编译 canteen-analyze 后重试。
          </p>
        </div>
      )}

      {state.status === 'error' && (
        <div className="rounded-xl border border-semantic-criticalSoft bg-semantic-criticalSoft/40 p-4 text-sm text-semantic-critical">
          {state.error || '分析失败'}
        </div>
      )}

      {state.status === 'ok' && state.data && (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          <ConfidencePanel data={state.data?.confidence_intervals} />
          <BottleneckPanel data={state.data?.bottleneck} />
          <HeadlinePanel data={state.data?.headline_metrics} />
        </div>
      )}
    </section>
  )
}

function ConfidencePanel({ data }) {
  const wait = read(data || {}, 'wait_time_minutes')
  if (!wait) return <Stub title="等待时间置信区间" message="后端未返回置信区间数据。" />
  const sample = read(wait, 'sample_count') || 0
  return (
    <div className="rounded-2xl border border-canvas-border bg-canvas-base p-4">
      <p className="field-label">等待时间 95% CI</p>
      <p className="mt-2 font-numeric text-3xl text-bjtu-700">
        {formatNumber(read(wait, 'mean'))} <span className="text-base text-slate-500">分钟</span>
      </p>
      <p className="mt-1 text-sm text-slate-500">
        区间 [{formatNumber(read(wait, 'lower'))}, {formatNumber(read(wait, 'upper'))}] / 样本 {sample}
      </p>
    </div>
  )
}

function BottleneckPanel({ data }) {
  if (!data) return <Stub title="瓶颈分" message="后端未返回瓶颈数据。" />
  const score = read(data, 'score') || 0
  return (
    <div className="rounded-2xl border border-canvas-border bg-canvas-base p-4">
      <p className="field-label">瓶颈分 (0-100)</p>
      <p className="mt-2 font-numeric text-3xl text-bjtu-700">{formatNumber(score, 1)}</p>
      <div className="mt-3 progress-bar">
        <div className="progress-bar-fill" style={{ width: `${Math.min(100, score)}%` }} />
      </div>
      <p className="mt-2 text-sm text-slate-500">
        Gini {formatNumber(read(data, 'gini_coefficient'), 2)} / 持续拥挤 {read(data, 'sustained_peak_minutes') || 0} 帧
        {read(data, 'worst_window_id') !== undefined && ` / 最堵窗口 ${read(data, 'worst_window_id') + 1}`}
      </p>
    </div>
  )
}

function HeadlinePanel({ data }) {
  if (!data) return null
  const items = [
    ['典型等待', formatNumber(read(data, 'typical_wait_time_minutes')), '分钟'],
    ['座位利用率', `${formatNumber((read(data, 'seat_utilization_rate') || 0) * 100, 1)}%`, ''],
    ['打包率', `${formatNumber((read(data, 'takeaway_rate') || 0) * 100, 1)}%`, ''],
    ['完成服务', read(data, 'served_count') || 0, '人']
  ]
  return (
    <div className="md:col-span-2 grid grid-cols-2 gap-3 sm:grid-cols-4">
      {items.map(([label, value, suffix]) => (
        <div key={label} className="rounded-2xl border border-canvas-border bg-canvas-base p-4">
          <p className="field-label">{label}</p>
          <p className="mt-2 font-numeric text-2xl text-bjtu-700">
            {value} <span className="text-sm text-slate-500">{suffix}</span>
          </p>
        </div>
      ))}
    </div>
  )
}

function Stub({ title, message }) {
  return (
    <div className="rounded-2xl border border-dashed border-canvas-border bg-canvas-base p-4">
      <p className="field-label">{title}</p>
      <p className="mt-2 text-sm text-slate-500">{message}</p>
    </div>
  )
}

export default AdvancedStatsPanel
