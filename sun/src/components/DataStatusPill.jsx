function DataStatusPill({ reportId, snapshotCount }) {
  const hasReport = Boolean(reportId)
  return (
    <div className="flex items-center gap-3 rounded-full border border-canvas-border bg-canvas-surface px-4 py-2 shadow-pill">
      <span className={`inline-block h-2 w-2 rounded-full ${hasReport ? 'bg-semantic-success' : 'bg-slate-300'}`} />
      <div className="leading-tight">
        <p className="text-[10px] font-medium uppercase tracking-wide text-slate-500">当前报告</p>
        <p className="text-sm font-semibold text-slate-800">
          {hasReport ? truncate(reportId) : '尚未运行仿真'}
        </p>
      </div>
      {hasReport && Number.isFinite(snapshotCount) && (
        <span className="pill">
          <span>{snapshotCount}</span>
          <span className="text-bjtu-500">帧</span>
        </span>
      )}
    </div>
  )
}

function truncate(value) {
  if (!value) return ''
  if (value.length <= 14) return value
  return `${value.slice(0, 8)}…${value.slice(-4)}`
}

export default DataStatusPill
