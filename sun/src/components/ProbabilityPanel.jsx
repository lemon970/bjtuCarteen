import { formatNumber, formatPercent, read } from '../utils/simulation'

function ProbabilityPanel({ summary }) {
  const model = read(summary, 'probability_model', 'probabilityModel') || {}
  const samples = read(summary, 'arrival_samples', 'arrivalSamples') || []
  const decisions = read(summary, 'takeaway_decision_records', 'takeawayDecisionRecords') || []
  const samplePreview = Array.isArray(samples) ? samples.slice(0, 8) : []
  const decisionPreview = Array.isArray(decisions) ? decisions.slice(-5).reverse() : []

  return (
    <div className="probability-panel">
      <div className="probability-grid">
        <div>
          <span>单位时间人数</span>
          <strong>{read(model, 'arrival_count_distribution', 'arrivalCountDistribution') || 'POISSON'}</strong>
        </div>
        <div>
          <span>到达间隔</span>
          <strong>{read(model, 'interarrival_distribution', 'interarrivalDistribution') || 'NEGATIVE_EXPONENTIAL'}</strong>
        </div>
        <div>
          <span>λ / 小时</span>
          <strong>{formatNumber(read(model, 'configured_arrival_lambda_per_hour', 'configuredArrivalLambdaPerHour') ?? 0, 1)}</strong>
        </div>
        <div>
          <span>间隔拟合度</span>
          <strong>{formatPercent(read(model, 'observed_interarrival_accuracy', 'observedInterarrivalAccuracy') ?? 0)}</strong>
        </div>
      </div>

      <div className="mini-table-grid">
        <div className="mini-table">
          <h3>到达间隔样本</h3>
          {samplePreview.map((item, index) => (
            <p key={`${read(item, 'time_seconds', 'timeSeconds')}-${index}`}>
              第 {read(item, 'minute') ?? 0} 分钟，间隔 {read(item, 'interval_seconds', 'intervalSeconds') ?? 0} 秒
            </p>
          ))}
        </div>
        <div className="mini-table">
          <h3>打包决策样本</h3>
          {decisionPreview.length === 0 ? (
            <p>暂无打包决策记录</p>
          ) : (
            decisionPreview.map((item) => (
              <p key={`${read(item, 'student_id', 'studentId')}-${read(item, 'time_seconds', 'timeSeconds')}`}>
                {read(item, 'takeaway') ? '打包' : '堂食'}，概率 {formatPercent(read(item, 'final_probability', 'finalProbability') ?? 0)}
              </p>
            ))
          )}
        </div>
      </div>
    </div>
  )
}

export default ProbabilityPanel
