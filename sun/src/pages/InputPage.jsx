import { MAX_SEATS, MAX_STUDENTS } from '../constants'
import { clamp, formatNumber, formatPercent, toNumber } from '../utils/simulation'

// [重构] 信息输入页改为参数驾驶舱，原因是原页面控件集中在左侧，右侧说明区无法承载实际配置反馈。
function InputPage({
  form,
  loading,
  message,
  onFieldChange,
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
  const windowCount = Math.max(1, Math.floor(toNumber(form.windowCount, 1)))
  const takeawayWindowCount = Math.min(windowCount, Math.max(0, Math.floor(toNumber(form.takeawayWindowCount, 0))))
  const normalWindowCount = Math.max(0, windowCount - takeawayWindowCount)
  const seatPressure = clamp(expectedArrivals / Math.max(1, toNumber(form.totalSeats, 1)), 0, 3)
  const serviceMean = Math.max(1, toNumber(form.serviceMean, 90))
  const takeawayMean = serviceMean * Math.max(1, toNumber(form.takeawayServiceTimeMultiplier, 1.2))

  return (
    <section className="input-dashboard">
      <form className="panel input-hero" onSubmit={onRun}>
        <div className="section-head">
          <div>
            <p className="eyebrow">SIMULATION CONTROL</p>
            <h2>信息输入</h2>
            <p>配置人流、窗口、座位和概率模型后运行仿真。到达率会自动同步为泊松 λ。</p>
          </div>
          <div className="button-row">
            <button type="button" className="ghost" onClick={onReset}>
              重置
            </button>
            <button type="button" className="secondary" onClick={onLoadLatest} disabled={loading}>
              最新报告
            </button>
            <button className="primary" type="submit" disabled={loading}>
              {loading ? '运行中...' : '运行仿真'}
            </button>
          </div>
        </div>
        {message && <div className="message">{message}</div>}
      </form>

      <div className="input-layout">
        <div className="input-sections">
          <ParameterSection title="场景与人流" note="峰值只改变到达时间分布，不改变总人数。">
            <label className="field-wide">
              仿真名称
              <input value={form.simulationName} onChange={(e) => onFieldChange('simulationName', e.target.value)} />
            </label>
            <NumberField label="仿真时长（小时）" min="0.1" step="0.1" value={form.duration} onChange={(value) => onFieldChange('duration', value)} />
            <NumberField label="到达率（人/小时）" min="0" step="1" value={form.arrivalRate} onChange={(value) => onFieldChange('arrivalRate', value)} />
            <label>
              到达间隔（秒）
              <input type="number" min="0" step="1" value={form.arrivalInterval} onChange={(e) => onFieldChange('arrivalInterval', e.target.value)} />
            </label>
            <label>
              随机种子
              <input type="number" step="1" value={form.seed} onChange={(e) => onFieldChange('seed', e.target.value)} />
            </label>
          </ParameterSection>

          <ParameterSection title="窗口与队列" note="打包窗口服务时间略长，普通窗口仍是堂食学生首选。">
            <NumberField label="窗口总数" min="1" step="1" value={form.windowCount} onChange={(value) => onFieldChange('windowCount', value)} />
            <NumberField label="打包窗口数" min="0" step="1" value={form.takeawayWindowCount} onChange={(value) => onFieldChange('takeawayWindowCount', value)} />
            <NumberField label="队列阈值（人）" min="0" step="1" value={form.queueLimit} onChange={(value) => onFieldChange('queueLimit', value)} />
            <NumberField label="打包服务倍率" min="1" step="0.05" value={form.takeawayServiceTimeMultiplier} onChange={(value) => onFieldChange('takeawayServiceTimeMultiplier', value)} />
          </ParameterSection>

          <ParameterSection title="座位与就餐" note="座位利用率按占用座位秒积分计算。">
            <NumberField label="座位总数" min="0" max={MAX_SEATS} step="1" value={form.totalSeats} onChange={(value) => onFieldChange('totalSeats', value)} />
            <NumberField label="学生上限" min="0" max={MAX_STUDENTS} step="1" value={form.totalStudents} onChange={(value) => onFieldChange('totalStudents', value)} />
            <NumberField label="就餐均值（秒）" min="1" step="1" value={form.diningMean} onChange={(value) => onFieldChange('diningMean', value)} />
            <NumberField label="成组到达概率" min="0" max="1" step="0.01" value={form.groupArrivalProb} onChange={(value) => onFieldChange('groupArrivalProb', value)} />
            <NumberField label="组团人数" min="1" step="1" value={form.partySize} onChange={(value) => onFieldChange('partySize', value)} />
          </ParameterSection>

          <ParameterSection title="概率与行为" note="基础打包率只作为低压场景锚点，高压场景由模型内部触发。">
            <NumberField label="基础打包概率" min="0" max="1" step="0.01" value={form.packProbability} onChange={(value) => onFieldChange('packProbability', value)} />
            <NumberField label="偏好下限" min="0" max="1" step="0.01" value={form.preferenceMin} onChange={(value) => onFieldChange('preferenceMin', value)} />
            <NumberField label="偏好上限" min="0" max="1" step="0.01" value={form.preferenceMax} onChange={(value) => onFieldChange('preferenceMax', value)} />
            <label>
              天气
              <select value={form.currentWeather} onChange={(e) => onFieldChange('currentWeather', e.target.value)}>
                <option value="sunny">晴天</option>
                <option value="cloudy">阴天</option>
                <option value="rainy">雨天</option>
              </select>
            </label>
            <NumberField label="天气影响系数" min="0" step="0.1" value={form.weatherImpactFactor} onChange={(value) => onFieldChange('weatherImpactFactor', value)} />
          </ParameterSection>

          <ParameterSection title="服务与分布" note="到达人数服从泊松过程，间隔服从负指数分布；服务和就餐采用截断正态分布。">
            <label>
              泊松 λ（人/小时）
              <input type="number" value={arrivalRate} readOnly />
            </label>
            <NumberField label="普通服务均值（秒）" min="1" step="1" value={form.serviceMean} onChange={(value) => onFieldChange('serviceMean', value)} />
            <NumberField label="服务下限（秒）" min="1" step="1" value={form.serviceMin} onChange={(value) => onFieldChange('serviceMin', value)} />
            <NumberField label="服务上限（秒）" min="1" step="1" value={form.serviceMax} onChange={(value) => onFieldChange('serviceMax', value)} />
            <NumberField label="就餐下限（秒）" min="1" step="1" value={form.diningMin} onChange={(value) => onFieldChange('diningMin', value)} />
            <NumberField label="就餐上限（秒）" min="1" step="1" value={form.diningMax} onChange={(value) => onFieldChange('diningMax', value)} />
          </ParameterSection>

          <ParameterSection title="双峰客流" note="课程高峰用于重分配到达时刻，保证总人数不被放大。">
            <label className="check-row field-wide">
              <input type="checkbox" checked={Boolean(form.peakEnabled)} onChange={(e) => onFieldChange('peakEnabled', e.target.checked)} />
              启用课程高峰
            </label>
            <NumberField label="午高峰开始（分钟）" min="0" step="1" value={form.lunchPeakStart} onChange={(value) => onFieldChange('lunchPeakStart', value)} />
            <NumberField label="午高峰结束（分钟）" min="0" step="1" value={form.lunchPeakEnd} onChange={(value) => onFieldChange('lunchPeakEnd', value)} />
            <NumberField label="晚高峰开始（分钟）" min="0" step="1" value={form.dinnerPeakStart} onChange={(value) => onFieldChange('dinnerPeakStart', value)} />
            <NumberField label="晚高峰结束（分钟）" min="0" step="1" value={form.dinnerPeakEnd} onChange={(value) => onFieldChange('dinnerPeakEnd', value)} />
            <NumberField label="午高峰倍率" min="1" step="0.1" value={form.lunchPeakMultiplier} onChange={(value) => onFieldChange('lunchPeakMultiplier', value)} />
            <NumberField label="晚高峰倍率" min="1" step="0.1" value={form.dinnerPeakMultiplier} onChange={(value) => onFieldChange('dinnerPeakMultiplier', value)} />
          </ParameterSection>

          <ParameterSection title="配置导入" note="仅导入配置文件，不在页面展示原始 JSON。">
            <label className="field-wide">
              导入配置文件
              <input type="file" accept=".json" onChange={onFileUpload} />
            </label>
          </ParameterSection>
        </div>

        <aside className="panel estimate-panel">
          <div>
            <p className="eyebrow">LIVE ESTIMATE</p>
            <h2>运行预估</h2>
            <p>这些数值会随输入实时变化，用于在运行前判断参数是否合理。</p>
          </div>
          <div className="estimate-grid">
            <EstimateCard label="预计总人数" value={expectedArrivals} note={studentLimit > 0 ? `上限 ${studentLimit} 人` : '未设置人数上限'} />
            <EstimateCard label="泊松 λ" value={formatNumber(arrivalRate, 0)} note="人/小时，与到达率同步" />
            <EstimateCard label="普通窗口" value={normalWindowCount} note={`打包窗口 ${takeawayWindowCount} 个`} />
            <EstimateCard label="基础打包率" value={formatPercent(form.packProbability, 0)} note="低压场景锚点" />
            <EstimateCard label="座位压力" value={`${formatNumber(seatPressure, 2)}x`} note={seatPressure > 1 ? '可能出现找座压力' : '座位压力较低'} />
            <EstimateCard label="打包服务均值" value={`${formatNumber(takeawayMean, 0)} 秒`} note={`普通窗口约 ${formatNumber(serviceMean, 0)} 秒`} />
          </div>
          <div className="model-note">
            <strong>模型约束</strong>
            <p>峰值曲线不改变总人数；打包窗口服务略长；打包概率受基础概率、个人偏好、座位压力、等待压力和队列压力共同约束。</p>
          </div>
        </aside>
      </div>
    </section>
  )
}

function ParameterSection({ title, note, children }) {
  return (
    <section className="panel parameter-card">
      <div className="card-title">
        <h3>{title}</h3>
        <p>{note}</p>
      </div>
      <div className="parameter-grid">{children}</div>
    </section>
  )
}

function NumberField({ label, value, onChange, ...props }) {
  return (
    <label>
      {label}
      <input type="number" value={value} onChange={(event) => onChange(event.target.value)} {...props} />
    </label>
  )
}

function EstimateCard({ label, value, note }) {
  return (
    <div className="estimate-card">
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{note}</small>
    </div>
  )
}

export default InputPage
