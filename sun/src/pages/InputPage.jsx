import { MAX_SEATS, MAX_STUDENTS } from '../constants'
import { clamp, formatNumber, formatPercent, read, toNumber } from '../utils/simulation'

const SCENARIO_EMOJI = {
  baseline_offpeak: '🍱',
  lunch_peak_pressure: '🔥',
  seat_shortage: '🪑',
  weather_takeaway_surge: '🌧️',
  pack_share_low: '📦',
  pack_share_high: '🥡',
  group_concentration: '👥',
  group_high_concentration: '👨‍👩‍👧‍👦',
  takeaway_intervention: '🥡',
  rain_emergency: '🌧️',
  weekend_relax: '🌤️'
}

function InputPage({
  form,
  loading,
  message,
  scenarios,
  selectedScenarioIds,
  onFieldChange,
  onLoadScenario,
  onToggleScenario,
  onRunScenarioBatch,
  onReset,
  onRun,
  onLoadLatest,
  onFileUpload
}) {
  const duration = Math.max(0, toNumber(form.duration, 0))
  const arrivalRate = Math.max(0, toNumber(form.arrivalRate, 0))
  const studentLimit = Math.min(MAX_STUDENTS, Math.max(0, Math.floor(toNumber(form.totalStudents, 0))))
  const expectedArrivals = studentLimit > 0
    ? Math.min(studentLimit, Math.round(arrivalRate * duration))
    : Math.round(arrivalRate * duration)
  const totalSeats = Math.max(1, toNumber(form.totalSeats, 1))
  const seatPressure = clamp(expectedArrivals / totalSeats, 0, 3)
  const seatPressurePct = Math.min(100, (seatPressure / 1.5) * 100)
  const serviceMean = Math.max(1, toNumber(form.serviceMean, 90))
  const takeawayMean = serviceMean * Math.max(1, toNumber(form.takeawayServiceTimeMultiplier, 1.2))
  const groupCount = Math.max(0, Math.floor(toNumber(form.groupCount, 0)))
  const groupMin = Math.max(1, Math.floor(toNumber(form.groupSizeMin, 1)))
  const groupMax = Math.max(groupMin, Math.floor(toNumber(form.groupSizeMax, groupMin)))
  const groupedStudents = form.groupEnabled ? groupCount * ((groupMin + groupMax) / 2) : 0

  return (
    <div className="space-y-8">
      <header className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <p className="text-xs uppercase tracking-widest text-bjtu-500">MODEL INPUT</p>
          <h1 className="mt-1 text-3xl font-semibold text-slate-900">信息输入</h1>
          <p className="mt-1 text-sm text-slate-500">先选择成套模型,再按需要微调参数。</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" className="btn-secondary" onClick={onReset}>重置</button>
          <button type="button" className="btn-secondary" onClick={onLoadLatest} disabled={loading}>读取最新报告</button>
          <button type="submit" form="single-run-form" className="btn-primary" disabled={loading}>
            {loading ? '运行中…' : '▶ 运行当前配置'}
          </button>
        </div>
      </header>

      {message && (
        <div className="card px-4 py-3 text-sm text-slate-700">
          {message}
        </div>
      )}

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[2fr_1fr]">
        <section className="panel">
          <div className="panel-title">
            <div>
              <h2>成套模型样例</h2>
              <p>由后端目录提供,可单独加载,也可成组运行比较。</p>
            </div>
            <button type="button" className="btn-primary" disabled={loading || !selectedScenarioIds.length} onClick={onRunScenarioBatch}>
              成组运行
            </button>
          </div>
          {scenarios === null && (
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 2xl:grid-cols-3">
              {Array.from({ length: 6 }).map((_, idx) => (
                <article key={idx} className="card-hover flex flex-col p-5 animate-pulse">
                  <div className="h-6 w-24 rounded bg-canvas-border" />
                  <div className="mt-4 h-4 w-40 rounded bg-canvas-border" />
                  <div className="mt-2 h-3 w-32 rounded bg-canvas-border" />
                  <div className="mt-6 h-9 w-full rounded bg-canvas-border" />
                </article>
              ))}
            </div>
          )}
          {Array.isArray(scenarios) && scenarios.length === 0 && (
            <div className="rounded-2xl border border-dashed border-semantic-warning bg-semantic-warningSoft/40 p-6 text-sm">
              <p className="font-semibold text-semantic-warning">未读取到场景目录</p>
              <p className="mt-1 text-slate-600">
                请确认后端 <code className="text-bjtu-700">/api/simulation/scenarios</code> 接口可用,然后刷新页面重试。
              </p>
            </div>
          )}
          {Array.isArray(scenarios) && scenarios.length > 0 && (
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 2xl:grid-cols-3">
              {scenarios.map((scenario) => {
                const checked = selectedScenarioIds.includes(scenario.id)
                return (
                  <article
                    key={scenario.id}
                    className={`card-hover flex flex-col p-5 ${checked ? 'ring-2 ring-bjtu-500' : ''}`}
                  >
                    <div className="flex items-start justify-between gap-2">
                      <span className="text-2xl" aria-hidden>{SCENARIO_EMOJI[scenario.id] || '📊'}</span>
                      <label className="flex items-center gap-2 text-xs text-slate-500">
                        <input
                          type="checkbox"
                          className="h-4 w-4 rounded border-canvas-border text-bjtu-600 focus:ring-bjtu-500"
                          checked={checked}
                          onChange={() => onToggleScenario(scenario.id)}
                        />
                        <span>{read(scenario, 'category') || '模型'}</span>
                      </label>
                    </div>
                    <h3 className="mt-3 text-lg font-semibold text-slate-900">{scenario.name}</h3>
                    <p className="mt-1 text-sm text-slate-500">{scenario.purpose}</p>
                    <dl className="mt-4 grid grid-cols-3 gap-2 text-xs">
                      <ScenarioMeta label="期望人数" value={read(scenario.expected_metrics, 'expected_arrivals', 'expectedArrivals') ?? '-'} />
                      <ScenarioMeta label="打包率" value={read(scenario.expected_metrics, 'takeaway_rate_range', 'takeawayRateRange') ?? '-'} />
                      <ScenarioMeta label="座位利用" value={read(scenario.expected_metrics, 'seat_utilization_range', 'seatUtilizationRange') ?? '-'} />
                    </dl>
                    <button type="button" className="btn-ghost mt-auto pt-4 w-full" onClick={() => onLoadScenario(scenario.id)}>
                      加载此模型
                    </button>
                  </article>
                )
              })}
            </div>
          )}
          {Array.isArray(scenarios) && scenarios.length > 0 && selectedScenarioIds.length === 0 && (
            <p className="mt-3 text-xs text-slate-500">勾选两个以上场景后即可批量运行。</p>
          )}
        </section>

        <aside className="panel">
          <div className="panel-title">
            <h2>运行预估</h2>
          </div>
          <div className="space-y-4">
            <EstimateRow label="预计到达" value={`${expectedArrivals} 人`} note={`${arrivalRate} 人/小时 × ${duration} 小时`} percent={Math.min(100, (expectedArrivals / 800) * 100)} />
            <EstimateRow label="座位压力" value={`${formatNumber(seatPressure, 2)}x`} note={seatPressure > 1 ? '可能触发找座压力' : '座位压力较低'} percent={seatPressurePct} accent={seatPressure > 1 ? 'amber' : 'bjtu'} />
            <EstimateRow label="基础打包率" value={formatPercent(form.packProbability, 0)} note="低压场景锚点" percent={Number(form.packProbability) * 100} />
            <EstimateRow label="打包服务均值" value={`${formatNumber(takeawayMean, 0)} 秒`} note={`普通约 ${formatNumber(serviceMean, 0)} 秒`} percent={Math.min(100, (takeawayMean / 240) * 100)} />
            <EstimateRow label="预计组数" value={`${form.groupEnabled ? groupCount : 0} 组`} note={form.groupEnabled ? `约 ${formatNumber(groupedStudents, 0)} 名成组学生` : '未启用成组模型'} percent={Math.min(100, (groupCount / 30) * 100)} />
          </div>
        </aside>
      </div>

      <form id="single-run-form" className="grid grid-cols-1 gap-6 lg:grid-cols-2" onSubmit={onRun}>
        <ParameterSection title="到达模型">
          <TextField label="仿真名称" value={form.simulationName} onChange={(value) => onFieldChange('simulationName', value)} />
          <NumberField label="仿真时长 / 小时" min="0.1" step="0.1" value={form.duration} onChange={(value) => onFieldChange('duration', value)} />
          <NumberField label="到达率 / 人每小时" min="0" step="1" value={form.arrivalRate} onChange={(value) => onFieldChange('arrivalRate', value)} />
          <NumberField label="随机种子" step="1" value={form.seed} onChange={(value) => onFieldChange('seed', value)} />
        </ParameterSection>

        <ParameterSection title="服务时间">
          <NumberField label="窗口总数" min="1" step="1" value={form.windowCount} onChange={(value) => onFieldChange('windowCount', value)} />
          <NumberField label="打包窗口数" min="0" step="1" value={form.takeawayWindowCount} onChange={(value) => onFieldChange('takeawayWindowCount', value)} />
          <NumberField label="普通服务均值 / 秒" min="1" step="1" value={form.serviceMean} onChange={(value) => onFieldChange('serviceMean', value)} />
          <NumberField label="打包服务倍数" min="1" step="0.05" value={form.takeawayServiceTimeMultiplier} onChange={(value) => onFieldChange('takeawayServiceTimeMultiplier', value)} />
        </ParameterSection>

        <ParameterSection title="座位策略">
          <NumberField label="座位总数" min="0" max={MAX_SEATS} step="1" value={form.totalSeats} onChange={(value) => onFieldChange('totalSeats', value)} />
          <NumberField label="学生上限" min="0" max={MAX_STUDENTS} step="1" value={form.totalStudents} onChange={(value) => onFieldChange('totalStudents', value)} />
          <NumberField label="就餐均值 / 秒" min="1" step="1" value={form.diningMean} onChange={(value) => onFieldChange('diningMean', value)} />
          <NumberField label="同行人数" min="1" step="1" value={form.partySize} onChange={(value) => onFieldChange('partySize', value)} />
        </ParameterSection>

        <ParameterSection title="干预规则">
          <NumberField label="队列阈值 / 人" min="0" step="1" value={form.queueLimit} onChange={(value) => onFieldChange('queueLimit', value)} />
          <NumberField label="基础打包概率" min="0" max="1" step="0.01" value={form.packProbability} onChange={(value) => onFieldChange('packProbability', value)} />
          <SelectField
            label="天气"
            value={form.currentWeather}
            options={[['sunny', '晴天'], ['cloudy', '阴天'], ['rainy', '雨天']]}
            onChange={(value) => onFieldChange('currentWeather', value)}
          />
          <NumberField label="天气影响系数" min="0" step="0.1" value={form.weatherImpactFactor} onChange={(value) => onFieldChange('weatherImpactFactor', value)} />
        </ParameterSection>

        <details className="panel lg:col-span-2" open>
          <summary className="cursor-pointer text-base font-semibold text-slate-900">成组学生</summary>
          <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            <CheckboxField label="启用成组到达" checked={form.groupEnabled} onChange={(value) => onFieldChange('groupEnabled', value)} />
            <CheckboxField label="优先邻近入座" checked={form.preferAdjacentSeats} onChange={(value) => onFieldChange('preferAdjacentSeats', value)} />
            <NumberField label="组数" min="0" step="1" value={form.groupCount} onChange={(value) => onFieldChange('groupCount', value)} />
            <NumberField label="每组最少人数" min="1" step="1" value={form.groupSizeMin} onChange={(value) => onFieldChange('groupSizeMin', value)} />
            <NumberField label="每组最多人数" min="1" step="1" value={form.groupSizeMax} onChange={(value) => onFieldChange('groupSizeMax', value)} />
            <NumberField label="组内到达时间窗 / 秒" min="0" step="1" value={form.groupArrivalSpreadSeconds} onChange={(value) => onFieldChange('groupArrivalSpreadSeconds', value)} />
            <NumberField label="组内行为关联" min="0" max="1" step="0.05" value={form.groupBehaviorCorrelation} onChange={(value) => onFieldChange('groupBehaviorCorrelation', value)} />
            <NumberField label="兼容成组概率" min="0" max="1" step="0.01" value={form.groupArrivalProb} onChange={(value) => onFieldChange('groupArrivalProb', value)} />
          </div>
        </details>

        <details className="panel lg:col-span-2">
          <summary className="cursor-pointer text-base font-semibold text-slate-900">高级参数</summary>
          <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4">
            <NumberField label="到达间隔 / 秒" min="0" step="1" value={form.arrivalInterval} onChange={(value) => onFieldChange('arrivalInterval', value)} />
            <NumberField label="服务下限 / 秒" min="1" step="1" value={form.serviceMin} onChange={(value) => onFieldChange('serviceMin', value)} />
            <NumberField label="服务上限 / 秒" min="1" step="1" value={form.serviceMax} onChange={(value) => onFieldChange('serviceMax', value)} />
            <NumberField label="就餐下限 / 秒" min="1" step="1" value={form.diningMin} onChange={(value) => onFieldChange('diningMin', value)} />
            <NumberField label="就餐上限 / 秒" min="1" step="1" value={form.diningMax} onChange={(value) => onFieldChange('diningMax', value)} />
            <NumberField label="步行均值 / 秒" min="0" step="1" value={form.walkTimeMean} onChange={(value) => onFieldChange('walkTimeMean', value)} />
            <NumberField label="拥挤惩罚" min="0" step="0.05" value={form.congestionPenalty} onChange={(value) => onFieldChange('congestionPenalty', value)} />
            <NumberField label="偏好下限" min="0" max="1" step="0.01" value={form.preferenceMin} onChange={(value) => onFieldChange('preferenceMin', value)} />
            <NumberField label="偏好上限" min="0" max="1" step="0.01" value={form.preferenceMax} onChange={(value) => onFieldChange('preferenceMax', value)} />
            <NumberField label="午峰开始 / 分" min="0" step="1" value={form.lunchPeakStart} onChange={(value) => onFieldChange('lunchPeakStart', value)} />
            <NumberField label="午峰结束 / 分" min="0" step="1" value={form.lunchPeakEnd} onChange={(value) => onFieldChange('lunchPeakEnd', value)} />
            <NumberField label="午峰倍数" min="1" step="0.1" value={form.lunchPeakMultiplier} onChange={(value) => onFieldChange('lunchPeakMultiplier', value)} />
            <NumberField label="晚峰开始 / 分" min="0" step="1" value={form.dinnerPeakStart} onChange={(value) => onFieldChange('dinnerPeakStart', value)} />
            <NumberField label="晚峰结束 / 分" min="0" step="1" value={form.dinnerPeakEnd} onChange={(value) => onFieldChange('dinnerPeakEnd', value)} />
            <NumberField label="晚峰倍数" min="1" step="0.1" value={form.dinnerPeakMultiplier} onChange={(value) => onFieldChange('dinnerPeakMultiplier', value)} />
            <label className="block text-sm">
              <span className="field-label">导入配置文件</span>
              <input type="file" accept=".json" onChange={onFileUpload} className="mt-1 block w-full text-sm" />
            </label>
          </div>
        </details>
      </form>
    </div>
  )
}

function ParameterSection({ title, children }) {
  return (
    <section className="panel">
      <div className="panel-title">
        <h2>{title}</h2>
      </div>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2">{children}</div>
    </section>
  )
}

function TextField({ label, value, onChange }) {
  return (
    <label className="block text-sm">
      <span className="field-label">{label}</span>
      <input className="input-control" value={value ?? ''} onChange={(event) => onChange(event.target.value)} />
    </label>
  )
}

function NumberField({ label, value, onChange, ...props }) {
  return (
    <label className="block text-sm">
      <span className="field-label">{label}</span>
      <input className="input-control font-numeric tabular-nums" type="number" value={value ?? ''} onChange={(event) => onChange(event.target.value)} {...props} />
    </label>
  )
}

function SelectField({ label, value, options, onChange }) {
  return (
    <label className="block text-sm">
      <span className="field-label">{label}</span>
      <select className="input-control" value={value} onChange={(event) => onChange(event.target.value)}>
        {options.map(([opt, lbl]) => (
          <option key={opt} value={opt}>{lbl}</option>
        ))}
      </select>
    </label>
  )
}

function CheckboxField({ label, checked, onChange }) {
  return (
    <label className="block text-sm">
      <span className="field-label invisible">·</span>
      <span className="mt-1 flex items-center gap-3 rounded-xl border border-canvas-border bg-canvas-base px-3 py-2 text-slate-700 h-[42px]">
        <input
          type="checkbox"
          className="h-4 w-4 rounded border-canvas-border text-bjtu-600 focus:ring-bjtu-500"
          checked={Boolean(checked)}
          onChange={(event) => onChange(event.target.checked)}
        />
        <span>{label}</span>
      </span>
    </label>
  )
}

function ScenarioMeta({ label, value }) {
  return (
    <div className="rounded-lg bg-canvas-base px-2 py-1.5">
      <dt className="text-[10px] uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="mt-0.5 font-numeric text-sm text-bjtu-700">{value}</dd>
    </div>
  )
}

function EstimateRow({ label, value, note, percent, accent }) {
  const fillClass = accent === 'amber' ? 'gradient-amber' : 'gradient-bjtu'
  return (
    <div>
      <div className="flex items-center justify-between text-sm">
        <span className="font-medium text-slate-700">{label}</span>
        <strong className="font-numeric tabular-nums text-bjtu-700">{value}</strong>
      </div>
      <div className="mt-1.5 progress-bar">
        <div className={`h-full ${fillClass}`} style={{ width: `${Math.max(4, percent || 0)}%` }} />
      </div>
      <p className="mt-1 text-xs text-slate-500">{note}</p>
    </div>
  )
}

export default InputPage
