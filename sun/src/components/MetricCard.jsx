function MetricCard({ title, value, benchmark, status = 'info', trend, explanation }) {
  return (
    <article className={`metric-card status-${status}`}>
      <div className="metric-head">
        <span>{title}</span>
        <em>{trend || statusLabel(status)}</em>
      </div>
      <strong>{value}</strong>
      {benchmark && <small>参考：{benchmark}</small>}
      {explanation && <p>{explanation}</p>}
    </article>
  )
}

function statusLabel(status) {
  if (status === 'normal') return '正常'
  if (status === 'warning') return '关注'
  if (status === 'critical') return '严重'
  return '信息'
}

export default MetricCard
