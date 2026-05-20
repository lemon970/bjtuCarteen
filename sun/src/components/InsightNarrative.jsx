function InsightNarrative({ title = '自动解读', items = [] }) {
  const visible = items.filter(Boolean)
  if (!visible.length) {
    return null
  }
  return (
    <section className="panel insight-panel">
      <div className="panel-title">
        <h2>{title}</h2>
      </div>
      <ul className="insight-list">
        {visible.map((item, index) => (
          <li key={`${item}-${index}`}>{item}</li>
        ))}
      </ul>
    </section>
  )
}

export default InsightNarrative
