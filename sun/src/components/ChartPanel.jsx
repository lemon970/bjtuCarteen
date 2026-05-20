function ChartPanel({ title, description, xLabel, yLabel, legend, children }) {
  return (
    <section className="panel">
      <div className="panel-title">
        <div>
          <h2>{title}</h2>
          {description && <p>{description}</p>}
        </div>
      </div>
      {children}
      <div className="chart-meta">
        {xLabel && <span>X 轴：{xLabel}</span>}
        {yLabel && <span>Y 轴：{yLabel}</span>}
        {legend && <span>图例：{legend}</span>}
      </div>
    </section>
  )
}

export default ChartPanel
