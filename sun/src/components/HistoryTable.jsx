import { read } from '../utils/simulation'

function HistoryTable({ page, onPage, loading }) {
  const items = page?.items || []
  if (!page) {
    return (
      <div className="empty-state">
        点击"读取事件快照"后,将按分页展示事件级历史数据。
      </div>
    )
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between text-xs text-slate-500">
        <span>第 {page.page} / {page.total_pages || 0} 页</span>
        <span>共 {page.total_items || 0} 条事件</span>
      </div>
      <div className="overflow-auto rounded-xl border border-canvas-border max-h-[420px]">
        <table className="table-base">
          <thead>
            <tr>
              <th>时间/秒</th>
              <th>排队</th>
              <th>座位</th>
              <th>到达</th>
              <th>完成服务</th>
              <th>事件说明</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item, index) => (
              <tr key={`${read(item, 'time_seconds', 'time') ?? index}-${index}`}>
                <td className="font-numeric tabular-nums">{read(item, 'time_seconds', 'time') ?? 0}</td>
                <td className="font-numeric tabular-nums">{read(item, 'total_queue_size', 'totalQueueSize') ?? 0}</td>
                <td className="font-numeric tabular-nums">{read(item, 'occupied_seats', 'occupiedSeats') ?? 0}</td>
                <td className="font-numeric tabular-nums">{read(item, 'arrived_count', 'arrivedCount') ?? 0}</td>
                <td className="font-numeric tabular-nums">{read(item, 'served_count', 'servedCount') ?? 0}</td>
                <td className="text-slate-600">{read(item, 'event_message', 'eventMessage') || '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="flex justify-end gap-2">
        <button type="button" className="btn-secondary" disabled={!page.has_previous || loading} onClick={() => onPage(page.page - 1)}>
          上一页
        </button>
        <button type="button" className="btn-secondary" disabled={!page.has_next || loading} onClick={() => onPage(page.page + 1)}>
          下一页
        </button>
      </div>
    </div>
  )
}

export default HistoryTable
