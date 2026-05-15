import { useEffect, useMemo, useState } from 'react'

import ChartPanel from '../components/ChartPanel'
import HistoryTable from '../components/HistoryTable'
import MetricCard from '../components/MetricCard'
import QueueBarChart from '../components/charts/QueueBarChart'
import ScenarioCompareTabs from '../components/ScenarioCompareTabs'
import SeatHeatmap from '../components/SeatHeatmap'
import SeatUtilizationLine from '../components/charts/SeatUtilizationLine'
import TakeawayRatePanel from '../components/TakeawayRatePanel'
import TimelinePlayer from '../components/TimelinePlayer'
import TrendChart from '../components/charts/TrendChart'
import WaitTimePanel from '../components/WaitTimePanel'
import { csvExportUrl } from '../api/simulationApi'
import { formatNumber, formatPercent, normalizePoint, read } from '../utils/simulation'
import { useEcharts } from '../utils/useEcharts'

function DisplayPage({ report, scenarioResults = [], historyPage, historyLoading, onLoadHistory, onLoadLatest }) {
  const summary = report?.summary || {}
  const config = report?.config || {}
  const reportId = read(report, 'report_id', 'reportId') || ''
  const timeline = Array.isArray(summary.timeline) ? summary.timeline : []
  const seatCells = read(summary, 'seat_cells', 'seatCells') || []

  const [selectedTimelineIndex, setSelectedTimelineIndex] = useState(0)
  const [isPlaying, setIsPlaying] = useState(false)
  const [playSpeedMs, setPlaySpeedMs] = useState(700)

  useEffect(() => {
    setSelectedTimelineIndex(timeline.length > 0 ? timeline.length - 1 : 0)
    setIsPlaying(false)
  }, [reportId, timeline.length])

  useEffect(() => {
    if (!isPlaying || timeline.length <= 1) {
      return undefined
    }
    const timer = window.setInterval(() => {
      setSelectedTimelineIndex((current) => {
        if (current >= timeline.length - 1) {
          setIsPlaying(false)
          return current
        }
        return current + 1
      })
    }, playSpeedMs)
    return () => window.clearInterval(timer)
  }, [isPlaying, playSpeedMs, timeline.length])

  const currentPoint = timeline[selectedTimelineIndex] || summary
  const currentFrame = useMemo(
    () => normalizePoint(currentPoint, selectedTimelineIndex),
    [currentPoint, selectedTimelineIndex]
  )
  const currentWindowQueues = read(currentPoint, 'window_queue_sizes', 'windowQueueSizes', 'queue_sizes', 'queueSizes') || []
  const currentWaitWindow = Number(read(currentPoint, 'avg_wait_minutes_window', 'avgWaitMinutesWindow')) || 0
  const currentWaitSamples = Number(read(currentPoint, 'wait_sample_count_window', 'waitSampleCountWindow')) || 0
  const typicalWait = read(summary, 'typical_wait_time_minutes', 'typicalWaitTimeMinutes', 'avg_wait_time_minutes', 'avgWaitTimeMinutes') ?? 0
  const waitStatus = read(read(summary, 'wait_time_insight', 'waitTimeInsight') || {}, 'status') || 'info'
  const utilization = read(summary, 'seat_utilization_rate', 'seatUtilizationRate') ?? 0
  const occupiedNow = read(currentFrame, 'seats') ?? 0
  const totalSeats = read(summary, 'total_seats', 'totalSeats') ?? read(currentPoint, 'total_seats', 'totalSeats') ?? 0
  const windowCount = (read(summary, 'window_served_counts', 'windowServedCounts') || []).length
  const windowTypes = read(summary, 'window_types', 'windowTypes') || []
  const windowServed = read(summary, 'window_served_counts', 'windowServedCounts') || []

  return (
    <div className="space-y-8">
      <header className="flex flex-wrap items-end gap-4 justify-between">
        <div>
          <p className="text-xs uppercase tracking-widest text-bjtu-500">RESULT DASHBOARD</p>
          <h1 className="mt-1 text-3xl font-semibold text-slate-900">数据展示</h1>
          <p className="mt-1 text-sm text-slate-500">按 KPI、时间轴、座位状态和异常归因组织运行结果。</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <button type="button" className="btn-secondary" onClick={onLoadLatest}>读取最新报告</button>
          {reportId && <a className="btn-ghost" href={csvExportUrl(reportId)}>⬇ 导出 CSV</a>}
        </div>
      </header>

      {scenarioResults.length > 0 && <ScenarioCompareTabs results={scenarioResults} />}

      <section className="grid grid-cols-1 gap-4 md:grid-cols-3">
        <BigStatCard
          label="当前占用座位"
          value={occupiedNow}
          suffix={totalSeats ? `/ ${totalSeats}` : ''}
          hint={`第 ${currentFrame.minute} 分钟快照`}
          accent="bjtu"
        />
        <BigStatCard
          label="当前工作窗口"
          value={windowCount}
          suffix="个"
          hint={`含 ${windowTypes.filter((t) => String(t).toUpperCase() === 'TAKEAWAY').length} 个打包窗口`}
          accent="amber"
        />
        <BigStatCard
          label="座位利用率"
          value={`${formatNumber(utilization * 100, 1)}`}
          suffix="%"
          hint="按座位秒积分"
          accent="teal"
        />
      </section>

      <TakeawayRatePanel summary={summary} config={config} />

      <section className="grid grid-cols-2 gap-4 lg:grid-cols-3 xl:grid-cols-6">
        <MetricCard title="到达人数" value={read(summary, 'arrived_count', 'arrivedCount') ?? 0} benchmark="由到达率和时长决定" />
        <MetricCard title="完成服务" value={read(summary, 'served_count', 'servedCount') ?? 0} benchmark="应接近到达人数" />
        <MetricCard title="典型等待" value={`${formatNumber(typicalWait)} 分钟`} benchmark="0-5 分钟正常" status={waitStatus} />
        <MetricCard title="座位利用率" value={formatPercent(utilization)} benchmark="高峰常见 40%-70%" />
        <MetricCard title="打包比例" value={formatPercent(read(summary, 'takeaway_rate', 'takeawayRate') ?? 0)} benchmark="晴天约 12%-20%" />
        <MetricCard title="峰值排队" value={read(summary, 'max_total_queue_size', 'maxTotalQueueSize') ?? 0} benchmark="判断窗口瓶颈" />
      </section>

      <GroupStatsPanel summary={summary} />

      <section className="panel">
        <div className="panel-title">
          <div>
            <h2>仿真时间轴</h2>
            <p>拖动滑块或播放,座位图、当前帧排队、KPI 会同步刷新。</p>
          </div>
        </div>
        <TimelinePlayer
          timeline={timeline}
          selectedIndex={selectedTimelineIndex}
          isPlaying={isPlaying}
          playSpeedMs={playSpeedMs}
          onSelect={(index) => {
            setSelectedTimelineIndex(index)
            setIsPlaying(false)
          }}
          onTogglePlay={() => setIsPlaying((value) => !value)}
          onReset={() => {
            setSelectedTimelineIndex(0)
            setIsPlaying(false)
          }}
          onSpeedChange={setPlaySpeedMs}
        />
        <p className="mt-4 text-sm text-slate-500">
          第 <strong className="text-bjtu-700">{currentFrame.minute}</strong> 分钟 · 占用
          <strong className="ml-1 text-bjtu-700">{currentFrame.seats}</strong> / 总 {totalSeats} 座位 · 当前总排队
          <strong className="ml-1 text-bjtu-700">{currentFrame.queue}</strong> 人 · 过去 5 分钟均值等待
          <strong className="ml-1 text-accent-teal">{formatNumber(currentWaitWindow, 1)}</strong> 分钟
          <span className="ml-1 text-xs text-slate-400">({currentWaitSamples} 人样本)</span>
        </p>
        <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-[3fr_2fr]">
          <div className="rounded-2xl border border-canvas-border bg-canvas-surface p-4">
            <div className="flex items-center justify-between">
              <h3 className="text-base font-semibold text-slate-900">座位状态</h3>
              <span className="text-xs text-slate-500">同步当前帧</span>
            </div>
            <div className="mt-3">
              <SeatHeatmap point={currentPoint} fallbackCells={seatCells} />
            </div>
          </div>
          <div className="rounded-2xl border border-canvas-border bg-canvas-surface p-4">
            <div className="flex items-center justify-between">
              <h3 className="text-base font-semibold text-slate-900">当前帧窗口排队</h3>
              <span className="text-xs text-slate-500">蓝=普通 / 琥珀=打包</span>
            </div>
            <CurrentFrameQueueChart queues={currentWindowQueues} windowTypes={windowTypes} />
          </div>
        </div>
      </section>

      <WaitTimePanel summary={summary} />

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <ChartPanel title="队列与占座趋势" description={reportId ? `报告编号:${reportId}` : '暂无报告。'}>
          <TrendChart timeline={timeline} />
        </ChartPanel>
        <ChartPanel title="座位占用率" description="按座位秒积分得到的百分比,叠加 70% 舒适阈值。">
          <SeatUtilizationLine timeline={timeline} />
        </ChartPanel>
      </div>

      <ChartPanel title="窗口服务量" description="蓝色为普通窗口,琥珀色为打包窗口,识别窗口结构是否均衡。">
        <QueueBarChart windowServedCounts={windowServed} windowTypes={windowTypes} />
      </ChartPanel>

      <section className="panel">
        <div className="panel-title">
          <div>
            <h2>事件快照</h2>
            <p>事件历史按分页读取,避免一次性传输大 JSON。</p>
          </div>
          <button type="button" className="btn-secondary" onClick={() => onLoadHistory(1)} disabled={!reportId || historyLoading}>
            {historyLoading ? '读取中…' : '读取事件快照'}
          </button>
        </div>
        <HistoryTable page={historyPage} onPage={onLoadHistory} loading={historyLoading} />
      </section>
    </div>
  )
}

function CurrentFrameQueueChart({ queues, windowTypes }) {
  const option = useMemo(() => {
    const labels = (queues || []).map((_, idx) => `窗口 ${idx + 1}`)
    const data = (queues || []).map((value, idx) => {
      const isTakeaway = String(windowTypes?.[idx] || '').toUpperCase() === 'TAKEAWAY'
      return {
        value: Math.max(0, Number(value) || 0),
        itemStyle: {
          color: isTakeaway ? '#f59e0b' : '#1e40af',
          borderRadius: [0, 6, 6, 0]
        }
      }
    })
    return {
      grid: { left: 64, right: 24, top: 12, bottom: 28 },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params) => {
          const item = params[0]
          const isTakeaway = String(windowTypes?.[item.dataIndex] || '').toUpperCase() === 'TAKEAWAY'
          return `${item.name}<br/>${isTakeaway ? '打包窗口' : '普通窗口'}<br/>当前排队 <strong>${item.value}</strong> 人`
        }
      },
      xAxis: {
        type: 'value',
        axisLabel: { color: '#64748b' },
        splitLine: { lineStyle: { color: '#e2e8f0' } },
        minInterval: 1
      },
      yAxis: {
        type: 'category',
        data: labels,
        inverse: true,
        axisLabel: { color: '#475569' },
        axisLine: { lineStyle: { color: '#cbd5e1' } }
      },
      series: [
        {
          type: 'bar',
          data,
          barMaxWidth: 18,
          label: { show: true, position: 'right', color: '#1e3a8a', fontFamily: 'JetBrains Mono', fontWeight: 600 }
        }
      ]
    }
  }, [queues, windowTypes])

  const containerRef = useEcharts(option, [option])

  if (!queues || queues.length === 0) {
    return <div className="empty-state mt-3">暂无窗口排队数据,先运行仿真。</div>
  }
  return <div ref={containerRef} className="mt-2 h-72 w-full" />
}

function BigStatCard({ label, value, suffix, hint, accent }) {
  const accentClass = {
    bjtu: 'text-bjtu-700',
    amber: 'text-accent-amber',
    teal: 'text-accent-teal'
  }[accent] || 'text-bjtu-700'
  return (
    <article className="card-hover p-6">
      <p className="field-label">{label}</p>
      <p className={`mt-3 stat-large ${accentClass}`}>
        {value}
        {suffix && <span className="ml-2 text-2xl text-slate-400">{suffix}</span>}
      </p>
      <p className="mt-2 text-xs text-slate-500">{hint}</p>
    </article>
  )
}

function GroupStatsPanel({ summary }) {
  const groupCount = read(summary, 'group_count', 'groupCount') ?? 0
  const groupedStudents = read(summary, 'grouped_student_count', 'groupedStudentCount') ?? 0
  const avgGroupSize = read(summary, 'avg_group_size', 'avgGroupSize') ?? 0
  const sameTableRate = read(summary, 'same_table_group_rate', 'sameTableGroupRate') ?? 0
  const splitRate = read(summary, 'split_group_rate', 'splitGroupRate') ?? 0
  const served = read(summary, 'served_count', 'servedCount') ?? 0
  const individualCount = Math.max(0, served - groupedStudents)
  const groupShare = served > 0 ? groupedStudents / served : 0

  if (groupCount === 0 && groupedStudents === 0) {
    return null
  }

  return (
    <section className="panel">
      <div className="panel-title">
        <div>
          <h2>成组学生指标</h2>
          <p>从到达分布、入座聚集度两条线对比"个体 vs 群体"。</p>
        </div>
      </div>
      <div className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-6">
        <MetricCard title="组数" value={groupCount} benchmark={`平均 ${formatNumber(avgGroupSize, 1)} 人/组`} />
        <MetricCard title="成组学生" value={groupedStudents} benchmark={`占已服务 ${formatPercent(groupShare)}`} />
        <MetricCard title="个体学生" value={individualCount} benchmark="单人到达" />
        <MetricCard title="同桌入座率" value={formatPercent(sameTableRate)} benchmark="越高越聚集" status={sameTableRate >= 0.6 ? 'success' : 'info'} />
        <MetricCard title="拆组率" value={formatPercent(splitRate)} benchmark="拆桌即不在同一桌" status={splitRate >= 0.4 ? 'warning' : 'info'} />
        <MetricCard title="平均组规模" value={formatNumber(avgGroupSize, 2)} benchmark="可与组内最少/最多人数对照" />
      </div>
    </section>
  )
}

export default DisplayPage
