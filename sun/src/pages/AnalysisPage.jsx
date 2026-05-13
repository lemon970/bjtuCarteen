import MetricCard from '../components/MetricCard'
import ProbabilityPanel from '../components/ProbabilityPanel'
import TimelineChart from '../components/TimelineChart'
import UtilizationChart from '../components/UtilizationChart'
import { buildAnalysis, formatNumber, formatPercent, read } from '../utils/simulation'

// [重构] 数据分析页从展示页拆出，原因是统计结论、参数复盘和拥挤分析属于独立视图。
function AnalysisPage({ report, payload, onLoadLatest }) {
  const summary = report?.summary || {}
  const config = report?.config || payload
  const base = config?.base_config || config?.baseConfig || {}
  const model = read(summary, 'probability_model', 'probabilityModel') || {}
  const analysis = buildAnalysis(summary)

  if (!report) {
    return (
      <section className="dashboard">
        <div className="section-head">
          <div>
            <h2>数据分析</h2>
            <p>请先运行仿真或读取最新报告，系统会基于当前报告生成概率分布、座位压力和打包原因分析。</p>
          </div>
          <button type="button" className="secondary" onClick={onLoadLatest}>
            读取最新报告
          </button>
        </div>
        <div className="empty-state">暂无可分析的仿真报告。</div>
      </section>
    )
  }

  return (
    <section className="dashboard">
      <div className="section-head">
        <div>
          <h2>数据分析</h2>
          <p>基于 summary 与 timeline 生成峰值、拥挤区间和窗口效率分析。</p>
        </div>
        <button type="button" className="secondary" onClick={onLoadLatest}>
          读取最新报告
        </button>
      </div>

      <div className="kpi-grid analysis-grid">
        <MetricCard title="峰值利用率" value={formatPercent(analysis.peak.seatRate)} note={`第 ${analysis.peak.minute} 分钟`} />
        <MetricCard title="最大总排队" value={analysis.maxQueue} />
        <MetricCard title="拥挤快照数" value={analysis.crowdedCount} note="利用率不低于 70%" />
        <MetricCard title="拥挤占比" value={formatPercent(analysis.crowdedRatio)} />
        <MetricCard title="模型拟合度" value={formatPercent(read(model, 'observed_interarrival_accuracy', 'observedInterarrivalAccuracy') ?? 0)} />
      </div>

      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>概率模型验证</h2>
            <p>显式记录泊松到达人数、负指数到达间隔和打包决策样本，用于验证仿真可信度。</p>
          </div>
        </div>
        <ProbabilityPanel summary={summary} />
      </section>

      <section className="panel stat-report">
        <div className="panel-head">
          <div>
            <h2>参数复盘</h2>
            <p>展示本次报告归一化后的核心配置，便于和输入页核对。</p>
          </div>
        </div>
        <div className="report-grid">
          <div className="param-table">
            <div><span>仿真时长</span><strong>{read(config, 'duration') ?? '-'}</strong></div>
            <div><span>到达率</span><strong>{read(config, 'arrival_rate', 'arrivalRate') ?? '-'}</strong></div>
            <div><span>窗口总数</span><strong>{read(base, 'window_count', 'windowCount') ?? '-'}</strong></div>
            <div><span>打包窗口</span><strong>{read(base, 'takeaway_window_count', 'takeawayWindowCount') ?? '-'}</strong></div>
            <div><span>座位总数</span><strong>{read(base, 'total_seats', 'totalSeats') ?? '-'}</strong></div>
            <div><span>学生上限</span><strong>{read(base, 'total_students', 'totalStudents') ?? '-'}</strong></div>
          </div>
          <div className="param-table">
            <div><span>堂食比例</span><strong>{formatPercent(read(summary, 'dine_in_rate', 'dineInRate') ?? 0)}</strong></div>
            <div><span>打包比例</span><strong>{formatPercent(read(summary, 'takeaway_rate', 'takeawayRate') ?? 0)}</strong></div>
            <div><span>普通窗口服务率</span><strong>{formatPercent(read(summary, 'normal_window_served_rate', 'normalWindowServedRate') ?? 0)}</strong></div>
            <div><span>打包窗口服务率</span><strong>{formatPercent(read(summary, 'takeaway_window_served_rate', 'takeawayWindowServedRate') ?? 0)}</strong></div>
            <div><span>平均总队列</span><strong>{formatNumber(read(summary, 'avg_total_queue_size', 'avgTotalQueueSize') ?? 0)}</strong></div>
            <div><span>平均占座</span><strong>{formatNumber(read(summary, 'avg_occupied_seats', 'avgOccupiedSeats') ?? 0)}</strong></div>
          </div>
        </div>
      </section>

      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>座位压力趋势</h2>
            <p>用百分比口径观察座位占用峰值、持续拥挤区间和释放速度。</p>
          </div>
        </div>
        <UtilizationChart timeline={summary.timeline || []} />
      </section>

      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>队列与占座对照</h2>
            <p>蓝线表示总排队人数，绿线表示占用座位数，用于判断窗口瓶颈是否传导到座位区。</p>
          </div>
        </div>
        <TimelineChart timeline={summary.timeline || []} />
      </section>

      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>分析结论</h2>
            <p>根据当前报告自动生成，便于答辩或测试报告引用。</p>
          </div>
        </div>
        <div className="analysis-notes">
          <p>峰值座位利用率为 {formatPercent(analysis.peak.seatRate)}，出现在第 {analysis.peak.minute} 分钟。</p>
          <p>拥挤快照数为 {analysis.crowdedCount}，占全部快照 {formatPercent(analysis.crowdedRatio)}。</p>
          <p>最大总排队人数为 {analysis.maxQueue}，平均等待时间为 {formatNumber(read(summary, 'avg_wait_time_minutes', 'avgWaitTimeMinutes') ?? 0)} 分钟。</p>
          <p>到达间隔模型拟合度为 {formatPercent(read(model, 'observed_interarrival_accuracy', 'observedInterarrivalAccuracy') ?? 0)}，分钟到达人数方差均值比为 {formatNumber(read(model, 'observed_minute_count_variance_mean_ratio', 'observedMinuteCountVarianceMeanRatio') ?? 0)}。</p>
        </div>
      </section>
    </section>
  )
}

export default AnalysisPage
