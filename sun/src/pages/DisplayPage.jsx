import HistoryTable from '../components/HistoryTable'
import MetricCard from '../components/MetricCard'
import ReplayPanel from '../components/ReplayPanel'
import TimelineChart from '../components/TimelineChart'
import { csvExportUrl } from '../api/simulationApi'
import { formatNumber, formatPercent, read } from '../utils/simulation'

// [重构] 数据展示页独立承载快照、回放和分页表格，原因是展示逻辑与输入表单解耦。
function DisplayPage({ report, historyPage, historyLoading, onLoadHistory, onLoadLatest }) {
  const summary = report?.summary || {}
  const reportId = read(report, 'report_id', 'reportId') || ''
  const timeline = summary.timeline || []
  const seatCells = read(summary, 'seat_cells', 'seatCells') || []

  return (
    <section className="dashboard">
      <div className="section-head">
        <div>
          <h2>数据展示</h2>
          <p>展示后端返回的轻量报告、分钟级趋势快照和事件级分页历史。</p>
        </div>
        <div className="button-row">
          <button type="button" className="secondary" onClick={onLoadLatest}>
            读取最新报告
          </button>
          {reportId && (
            <a className="secondary link-button" href={csvExportUrl(reportId)}>
              导出 CSV
            </a>
          )}
        </div>
      </div>

      <div className="kpi-grid">
        <MetricCard title="到达人数" value={read(summary, 'arrived_count', 'arrivedCount') ?? 0} />
        <MetricCard title="完成服务" value={read(summary, 'served_count', 'servedCount') ?? 0} />
        <MetricCard title="放弃人数" value={read(summary, 'abandoned_count', 'abandonedCount') ?? 0} />
        <MetricCard title="平均等待" value={`${formatNumber(read(summary, 'avg_wait_time_minutes', 'avgWaitTimeMinutes') ?? 0)} 分钟`} />
        <MetricCard title="座位利用率" value={formatPercent(read(summary, 'seat_utilization_rate', 'seatUtilizationRate') ?? 0)} />
        <MetricCard title="打包比例" value={formatPercent(read(summary, 'takeaway_rate', 'takeawayRate') ?? 0)} />
        <MetricCard title="峰值排队" value={read(summary, 'max_total_queue_size', 'maxTotalQueueSize') ?? 0} />
      </div>

      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>趋势快照</h2>
            <p>{reportId ? `报告编号：${reportId}` : '暂无报告，请先运行仿真。'}</p>
          </div>
        </div>
        <TimelineChart timeline={timeline} />
      </section>

      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>时间轴回放</h2>
            <p>按后端 timeline 快照逐点播放，观察队列和座位变化。</p>
          </div>
        </div>
        <ReplayPanel timeline={timeline} seatCells={seatCells} />
      </section>

      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>事件快照</h2>
            <p>事件级历史数据量较大，按页读取以保持页面稳定。</p>
          </div>
          <button type="button" className="secondary" onClick={() => onLoadHistory(1)} disabled={!reportId || historyLoading}>
            {historyLoading ? '读取中...' : '读取事件快照'}
          </button>
        </div>
        <HistoryTable page={historyPage} onPage={onLoadHistory} loading={historyLoading} />
      </section>
    </section>
  )
}

export default DisplayPage
