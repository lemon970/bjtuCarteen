import AdvancedStatsPanel from '../components/AdvancedStatsPanel'
import ChartPanel from '../components/ChartPanel'
import InsightNarrative from '../components/InsightNarrative'
import MetricCard from '../components/MetricCard'
import SeatUtilizationLine from '../components/charts/SeatUtilizationLine'
import TrendChart from '../components/charts/TrendChart'
import WaitTimePanel from '../components/WaitTimePanel'
import { buildAnalysis, formatNumber, formatPercent, read } from '../utils/simulation'

function AnalysisPage({ report, scenarioResults = [], payload, onLoadLatest }) {
  const summary = report?.summary || {}
  const config = report?.config || payload
  const base = config?.base_config || config?.baseConfig || {}
  const model = read(summary, 'probability_model', 'probabilityModel') || {}
  const insight = read(summary, 'wait_time_insight', 'waitTimeInsight') || {}
  const analysis = buildAnalysis(summary)
  const reportId = read(report, 'report_id', 'reportId') || ''
  const typicalWait = read(summary, 'typical_wait_time_minutes', 'typicalWaitTimeMinutes', 'avg_wait_time_minutes', 'avgWaitTimeMinutes') ?? 0
  const arrivalSampleCount = read(model, 'arrival_sample_count', 'arrivalSampleCount') ?? 0
  const interarrivalAccuracy = read(model, 'observed_interarrival_accuracy', 'observedInterarrivalAccuracy') ?? 0
  const lowSample = arrivalSampleCount > 0 && arrivalSampleCount < 30

  if (!report) {
    return (
      <div className="space-y-6">
        <header className="flex flex-wrap items-end gap-4 justify-between">
          <div>
            <p className="text-xs uppercase tracking-widest text-bjtu-500">MODEL ANALYSIS</p>
            <h1 className="mt-1 text-3xl font-semibold text-slate-900">模型分析</h1>
            <p className="mt-1 text-sm text-slate-500">请先运行仿真或读取最新报告。</p>
          </div>
          <button type="button" className="btn-secondary" onClick={onLoadLatest}>读取最新报告</button>
        </header>
        <div className="empty-state">暂无可分析的仿真报告。</div>
      </div>
    )
  }

  return (
    <div className="space-y-8">
      <header className="flex flex-wrap items-end gap-4 justify-between">
        <div>
          <p className="text-xs uppercase tracking-widest text-bjtu-500">MODEL ANALYSIS</p>
          <h1 className="mt-1 text-3xl font-semibold text-slate-900">模型分析</h1>
          <p className="mt-1 text-sm text-slate-500">按结论摘要、等待画像和高级统计组织。</p>
        </div>
        <button type="button" className="btn-secondary" onClick={onLoadLatest}>读取最新报告</button>
      </header>

      <section className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-5">
        <MetricCard title="等待状态" value={`${formatNumber(typicalWait)} 分钟`} benchmark={read(insight, 'primary_reason', 'primaryReason') || '等待体验模型'} status={read(insight, 'status') || 'info'} />
        <MetricCard title="峰值利用率" value={formatPercent(analysis.peak.seatRate)} benchmark={`第 ${analysis.peak.minute} 分钟`} />
        <MetricCard title="最大总排队" value={analysis.maxQueue} benchmark="判断窗口瓶颈" />
        <MetricCard title="拥挤占比" value={formatPercent(analysis.crowdedRatio)} benchmark="利用率不低于 70%" />
        <MetricCard title="到达模型拟合" value={lowSample ? '样本不足' : formatPercent(interarrivalAccuracy)} benchmark={lowSample ? `仅 ${arrivalSampleCount} 个到达样本,提高时长或到达率后再看` : '越接近 100% 越好'} status={lowSample ? 'info' : undefined} />
      </section>

      {scenarioResults.length > 0 && <ScenarioInsights results={scenarioResults} />}

      <InsightNarrative
        title="结论摘要"
        items={[
          `典型等待 ${formatNumber(typicalWait)} 分钟,全量均值 ${formatNumber(read(summary, 'raw_avg_wait_time_minutes', 'rawAvgWaitTimeMinutes', 'avg_wait_time_minutes', 'avgWaitTimeMinutes') ?? 0)} 分钟。`,
          `峰值座位利用率 ${formatPercent(read(summary, 'peak_seat_utilization_rate', 'peakSeatUtilizationRate') ?? analysis.peak.seatRate)},稳态(中段 80%)均值 ${formatPercent(read(summary, 'steady_state_seat_utilization', 'steadyStateSeatUtilization') ?? 0)},时间加权均值 ${formatPercent(read(summary, 'seat_time_weighted_utilization', 'seatTimeWeightedUtilization') ?? 0)}。`,
          `座位翻台率 ${formatNumber(read(summary, 'seat_turnover_rate', 'seatTurnoverRate') ?? 0, 2)} 人/座(单座位平均接待人次,可大于 1)。`,
          `最大总排队人数 ${analysis.maxQueue},到达间隔模型拟合度 ${lowSample ? `样本不足(${arrivalSampleCount})` : formatPercent(interarrivalAccuracy)}。`
        ]}
      />

      <WaitTimePanel summary={summary} />

      <AdvancedStatsPanel reportId={reportId} />

      <section className="panel">
        <div className="panel-title">
          <div>
            <h2>打包决策解释</h2>
            <p>优先展示模型触发样本;强制窗口策略样本会标注为"不适用",避免误读。</p>
          </div>
        </div>
        <DecisionTable decisions={read(summary, 'takeaway_decision_records', 'takeawayDecisionRecords') || []} />
      </section>

      <section className="panel">
        <div className="panel-title">
          <div>
            <h2>参数复盘</h2>
            <p>报告归一化后的核心配置。</p>
          </div>
        </div>
        <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
          <ParamList items={[
            ['仿真时长', read(config, 'duration') ?? '-'],
            ['到达率', read(config, 'arrival_rate', 'arrivalRate') ?? '-'],
            ['窗口总数', read(base, 'window_count', 'windowCount') ?? '-'],
            ['打包窗口', read(base, 'takeaway_window_count', 'takeawayWindowCount') ?? '-'],
            ['座位总数', read(base, 'total_seats', 'totalSeats') ?? '-'],
            ['学生上限', read(base, 'total_students', 'totalStudents') ?? '-']
          ]} />
          <ParamList items={[
            ['堂食比例', formatPercent(read(summary, 'dine_in_rate', 'dineInRate') ?? 0)],
            ['打包比例', formatPercent(read(summary, 'takeaway_rate', 'takeawayRate') ?? 0)],
            ['成组数', read(summary, 'group_count', 'groupCount') ?? 0],
            ['成组学生', read(summary, 'grouped_student_count', 'groupedStudentCount') ?? 0],
            ['同桌组率', formatPercent(read(summary, 'same_table_group_rate', 'sameTableGroupRate') ?? 0)],
            ['拆组率', formatPercent(read(summary, 'split_group_rate', 'splitGroupRate') ?? 0)]
          ]} />
        </div>
      </section>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <ChartPanel title="座位压力趋势" description="观察占用峰值、持续拥挤区间和释放速度。">
          <SeatUtilizationLine timeline={summary.timeline || []} />
        </ChartPanel>
        <ChartPanel title="队列与占座对照" description="判断窗口瓶颈是否传导到座位区。">
          <TrendChart timeline={summary.timeline || []} />
        </ChartPanel>
      </div>
    </div>
  )
}

function DecisionTable({ decisions }) {
  const rows = prioritizeModelRows(Array.isArray(decisions) ? decisions : [])
  if (!rows.length) {
    return <div className="empty-state">暂无打包决策样本。</div>
  }
  return (
    <div className="overflow-auto rounded-xl border border-canvas-border">
      <table className="table-base">
        <thead>
          <tr>
            <th>路径</th>
            <th>基础概率</th>
            <th>最终概率</th>
            <th>座位因子</th>
            <th>等待因子</th>
            <th>队列因子</th>
            <th>随机数</th>
            <th>结果</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((item, index) => {
            const reason = read(item, 'window_choice_reason', 'windowChoiceReason') || read(item, 'reason') || '-'
            const isForced = String(reason).includes('强制') || String(reason).includes('窗口策略')
            return (
              <tr key={`${read(item, 'student_id', 'studentId')}-${index}`}>
                <td className="whitespace-pre-wrap text-xs text-slate-600">{reason}</td>
                <td className="font-numeric tabular-nums">{formatPercent(read(item, 'base_probability', 'baseProbability') ?? 0)}</td>
                <td className="font-numeric tabular-nums">{formatPercent(read(item, 'final_probability', 'finalProbability') ?? 0)}</td>
                <td className="font-numeric tabular-nums">{isForced ? '不适用' : formatNumber(read(item, 'seat_pressure_factor', 'seatPressureFactor') ?? 0, 3)}</td>
                <td className="font-numeric tabular-nums">{isForced ? '不适用' : formatNumber(read(item, 'wait_pressure_factor', 'waitPressureFactor') ?? 0, 3)}</td>
                <td className="font-numeric tabular-nums">{isForced ? '不适用' : formatNumber(read(item, 'queue_pressure_factor', 'queuePressureFactor') ?? 0, 3)}</td>
                <td className="font-numeric tabular-nums">{formatNumber(read(item, 'random_roll', 'randomRoll') ?? 0, 3)}</td>
                <td>
                  <span className={`pill ${read(item, 'takeaway') ? 'bg-accent-amberSoft text-semantic-warning ring-semantic-warning/30' : 'bg-bjtu-50 text-bjtu-700 ring-bjtu-200'}`}>
                    {read(item, 'takeaway') ? '打包' : '堂食'}
                  </span>
                </td>
                <td className="whitespace-pre-wrap text-xs text-slate-600">{read(item, 'decision_reason', 'decisionReason') || '-'}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function prioritizeModelRows(decisions) {
  const modelRows = decisions.filter((item) => {
    const reason = String(read(item, 'window_choice_reason', 'windowChoiceReason') || read(item, 'reason') || '')
    return !reason.includes('强制') && !reason.includes('窗口策略')
  })
  const factorMagnitude = (item) => (
    Math.abs(read(item, 'seat_pressure_factor', 'seatPressureFactor') ?? 0) +
    Math.abs(read(item, 'wait_pressure_factor', 'waitPressureFactor') ?? 0) +
    Math.abs(read(item, 'queue_pressure_factor', 'queuePressureFactor') ?? 0)
  )
  const sortedModelRows = [...modelRows].sort((a, b) => factorMagnitude(b) - factorMagnitude(a))
  const fallbackRows = decisions.filter((item) => !modelRows.includes(item))
  return [...sortedModelRows, ...fallbackRows].slice(0, 8)
}

function ScenarioInsights({ results }) {
  const best = [...results].sort((a, b) => (
    (read(a.summary, 'typical_wait_time_minutes', 'typicalWaitTimeMinutes') ?? 0) -
    (read(b.summary, 'typical_wait_time_minutes', 'typicalWaitTimeMinutes') ?? 0)
  ))[0]
  return (
    <InsightNarrative
      title="模型组对比摘要"
      items={[
        best ? `当前批次典型等待最低的是"${best.scenario_name}"。` : '',
        `本批次共运行 ${results.length} 个模型,可用于报告中的外部对比。`
      ]}
    />
  )
}

function ParamList({ items }) {
  return (
    <dl className="space-y-2 text-sm">
      {items.map(([label, value]) => (
        <div key={label} className="flex items-center justify-between rounded-xl bg-canvas-base px-3 py-2">
          <dt className="text-slate-500">{label}</dt>
          <dd className="font-numeric font-semibold tabular-nums text-bjtu-700">{value}</dd>
        </div>
      ))}
    </dl>
  )
}

export default AnalysisPage
