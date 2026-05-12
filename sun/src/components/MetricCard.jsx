// [重构] 将 KPI 卡片拆成独立组件，原因是展示页和分析页都会复用同一视觉结构。
function MetricCard({ title, value, note }) {
  return (
    <article className="metric-card">
      <span>{title}</span>
      <strong>{value}</strong>
      {note && <small>{note}</small>}
    </article>
  )
}

export default MetricCard
