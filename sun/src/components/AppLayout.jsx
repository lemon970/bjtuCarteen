import { ROUTES } from '../constants'

// [重构] 统一三页面导航结构，原因是原单界面功能过密且不利于演示分工。
function AppLayout({ activePage, onNavigate, reportId, children }) {
  return (
    <main className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">北京交通大学食堂仿真系统</p>
          <h1>食堂仿真综合控制台</h1>
        </div>
        <div className="report-badge">
          <span>当前报告</span>
          <strong>{reportId || '尚未生成'}</strong>
        </div>
      </header>

      <nav className="nav-tabs" aria-label="页面导航">
        {ROUTES.map((route) => (
          <button
            type="button"
            key={route.key}
            className={activePage === route.key ? 'nav-tab active' : 'nav-tab'}
            onClick={() => onNavigate(route.key)}
          >
            <span>{route.label}</span>
            <small>{route.description}</small>
          </button>
        ))}
      </nav>

      {children}
    </main>
  )
}

export default AppLayout
