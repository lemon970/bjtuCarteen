import { read } from '../utils/simulation'

// [重构] 历史快照独立为分页表格，原因是后端 history 只能分页读取，不能再一次性展示完整 JSON。
function HistoryTable({ page, onPage, loading }) {
  const items = page?.items || []
  if (!page) {
    return <div className="empty-state">点击“读取事件快照”后，将按分页展示事件级历史数据。</div>
  }

  return (
    <div>
      <div className="history-meta">
        <span>第 {page.page} / {page.total_pages || 0} 页</span>
        <span>共 {page.total_items || 0} 条</span>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>时间/秒</th>
              <th>队列</th>
              <th>座位</th>
              <th>到达</th>
              <th>完成服务</th>
              <th>事件说明</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item, index) => (
              <tr key={`${read(item, 'time_seconds', 'time') ?? index}-${index}`}>
                <td>{read(item, 'time_seconds', 'time') ?? 0}</td>
                <td>{read(item, 'total_queue_size', 'totalQueueSize') ?? 0}</td>
                <td>{read(item, 'occupied_seats', 'occupiedSeats') ?? 0}</td>
                <td>{read(item, 'arrived_count', 'arrivedCount') ?? 0}</td>
                <td>{read(item, 'served_count', 'servedCount') ?? 0}</td>
                <td>{read(item, 'event_message', 'eventMessage') || '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="pager">
        <button type="button" className="secondary" disabled={!page.has_previous || loading} onClick={() => onPage(page.page - 1)}>
          上一页
        </button>
        <button type="button" className="secondary" disabled={!page.has_next || loading} onClick={() => onPage(page.page + 1)}>
          下一页
        </button>
      </div>
    </div>
  )
}

export default HistoryTable
