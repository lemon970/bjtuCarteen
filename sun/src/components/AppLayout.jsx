import { ROUTES } from '../constants'
import DataStatusPill from './DataStatusPill'

function AppLayout({ activePage, onNavigate, reportId, snapshotCount, children }) {
  return (
    <div className="min-h-screen flex flex-col">
      <header className="sticky top-0 z-30 bg-canvas-surface/95 backdrop-blur border-b border-canvas-border">
        <div className="mx-auto max-w-[1480px] px-6 py-4 flex flex-wrap items-center gap-4">
          <div className="flex items-center gap-3">
            <span className="inline-flex h-12 w-12 items-center justify-center rounded-2xl gradient-bjtu text-white font-display font-bold text-lg shadow-pill">
              BJ
            </span>
            <div className="leading-tight">
              <p className="text-xs font-medium text-bjtu-700">BJTU · 信息工程学院</p>
              <h1 className="text-lg font-semibold text-slate-900">食堂就餐仿真工作台</h1>
            </div>
          </div>

          <nav className="ml-2 flex items-center gap-1 rounded-2xl bg-canvas-base p-1" aria-label="页面导航">
            {ROUTES.map((route) => (
              <button
                type="button"
                key={route.key}
                onClick={() => onNavigate(route.key)}
                className={`rounded-xl px-4 py-2 text-sm font-medium transition ${
                  activePage === route.key ? 'tab-active' : 'tab-idle'
                }`}
              >
                {route.label}
              </button>
            ))}
          </nav>

          <div className="ml-auto flex items-center gap-3">
            <DataStatusPill reportId={reportId} snapshotCount={snapshotCount} />
          </div>
        </div>
      </header>

      <main className="flex-1 mx-auto w-full max-w-[1480px] px-6 py-8">{children}</main>

      <footer className="border-t border-canvas-border bg-canvas-surface/60">
        <div className="mx-auto max-w-[1480px] px-6 py-3 text-xs text-slate-500">
          仿真后端 v1.9 / C++ 后处理已接入 / 仅供教学演示。
        </div>
      </footer>
    </div>
  )
}

export default AppLayout
