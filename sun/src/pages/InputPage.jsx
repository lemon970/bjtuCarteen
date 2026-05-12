import { MAX_STUDENTS } from '../constants'

// [重构] 信息输入页独立承载表单和上传功能，原因是用户要求三界面拆分且前端不再展示 JSON。
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
  return (
    <section className="page-grid input-page">
      <form className="panel form-panel" onSubmit={onRun}>
        <div className="panel-head">
          <div>
            <h2>信息输入</h2>
            <p>填写食堂规模、到达率、服务时间与天气参数后运行仿真。</p>
          </div>
          <button type="button" className="ghost" onClick={onReset}>
            重置
          </button>
        </div>

        <label>
          仿真名称
          <input value={form.simulationName} onChange={(e) => onFieldChange('simulationName', e.target.value)} />
        </label>

        <div className="grid-2">
          <label>
            仿真时长（小时）
            <input type="number" min="0.1" step="0.1" value={form.duration} onChange={(e) => onFieldChange('duration', e.target.value)} />
          </label>
          <label>
            到达率（人/小时）
            <input type="number" min="0" step="1" value={form.arrivalRate} onChange={(e) => onFieldChange('arrivalRate', e.target.value)} />
          </label>
        </div>

        <div className="form-section">
          <h3>概率分布模型</h3>
          <div className="grid-2">
            <label>
              泊松 λ（人/小时）
              <input type="number" min="0" step="1" value={form.arrivalLambda} onChange={(e) => onFieldChange('arrivalLambda', e.target.value)} />
            </label>
            <label>
              指数服务均值 μ（秒）
              <input type="number" min="1" step="1" value={form.serviceMean} onChange={(e) => onFieldChange('serviceMean', e.target.value)} />
            </label>
          </div>
          <label>
            指数用餐均值（秒）
            <input type="number" min="1" step="1" value={form.diningMean} onChange={(e) => onFieldChange('diningMean', e.target.value)} />
          </label>
        </div>

        <div className="grid-2">
          <label>
            窗口总数
            <input type="number" min="1" step="1" value={form.windowCount} onChange={(e) => onFieldChange('windowCount', e.target.value)} />
          </label>
          <label>
            打包窗口数
            <input type="number" min="0" step="1" value={form.takeawayWindowCount} onChange={(e) => onFieldChange('takeawayWindowCount', e.target.value)} />
          </label>
        </div>

        <div className="grid-2">
          <label>
            座位总数
            <input type="number" min="0" step="1" value={form.totalSeats} onChange={(e) => onFieldChange('totalSeats', e.target.value)} />
          </label>
          <label>
            学生上限
            <input type="number" min="0" max={MAX_STUDENTS} step="1" value={form.totalStudents} onChange={(e) => onFieldChange('totalStudents', e.target.value)} />
          </label>
        </div>

        <div className="grid-2">
          <label>
            队列阈值
            <input type="number" min="0" step="1" value={form.queueLimit} onChange={(e) => onFieldChange('queueLimit', e.target.value)} />
          </label>
          <label>
            打包概率
            <input type="number" min="0" max="1" step="0.01" value={form.packProbability} onChange={(e) => onFieldChange('packProbability', e.target.value)} />
          </label>
        </div>

        <div className="grid-2">
          <label>
            服务时间下限（秒）
            <input type="number" min="1" step="1" value={form.serviceMin} onChange={(e) => onFieldChange('serviceMin', e.target.value)} />
          </label>
          <label>
            服务时间上限（秒）
            <input type="number" min="1" step="1" value={form.serviceMax} onChange={(e) => onFieldChange('serviceMax', e.target.value)} />
          </label>
        </div>

        <div className="grid-2">
          <label>
            用餐时间下限（秒）
            <input type="number" min="1" step="1" value={form.diningMin} onChange={(e) => onFieldChange('diningMin', e.target.value)} />
          </label>
          <label>
            用餐时间上限（秒）
            <input type="number" min="1" step="1" value={form.diningMax} onChange={(e) => onFieldChange('diningMax', e.target.value)} />
          </label>
        </div>

        <div className="grid-2">
          <label>
            天气
            <select value={form.currentWeather} onChange={(e) => onFieldChange('currentWeather', e.target.value)}>
              <option value="sunny">晴天</option>
              <option value="cloudy">阴天</option>
              <option value="rainy">雨天</option>
            </select>
          </label>
          <label>
            天气影响系数
            <input type="number" min="0" step="0.1" value={form.weatherImpactFactor} onChange={(e) => onFieldChange('weatherImpactFactor', e.target.value)} />
          </label>
        </div>

        <div className="grid-2">
          <label>
            到达间隔（秒）
            <input type="number" min="0" step="1" value={form.arrivalInterval} onChange={(e) => onFieldChange('arrivalInterval', e.target.value)} />
          </label>
          <label>
            随机种子
            <input type="number" step="1" value={form.seed} onChange={(e) => onFieldChange('seed', e.target.value)} />
          </label>
        </div>

        <div className="form-section">
          <h3>双峰客流</h3>
          <label className="check-row">
            <input type="checkbox" checked={Boolean(form.peakEnabled)} onChange={(e) => onFieldChange('peakEnabled', e.target.checked)} />
            启用课程高峰叠加
          </label>
          <div className="grid-2">
            <label>
              午高峰开始（分钟）
              <input type="number" min="0" step="1" value={form.lunchPeakStart} onChange={(e) => onFieldChange('lunchPeakStart', e.target.value)} />
            </label>
            <label>
              午高峰结束（分钟）
              <input type="number" min="0" step="1" value={form.lunchPeakEnd} onChange={(e) => onFieldChange('lunchPeakEnd', e.target.value)} />
            </label>
          </div>
          <div className="grid-2">
            <label>
              晚高峰开始（分钟）
              <input type="number" min="0" step="1" value={form.dinnerPeakStart} onChange={(e) => onFieldChange('dinnerPeakStart', e.target.value)} />
            </label>
            <label>
              晚高峰结束（分钟）
              <input type="number" min="0" step="1" value={form.dinnerPeakEnd} onChange={(e) => onFieldChange('dinnerPeakEnd', e.target.value)} />
            </label>
          </div>
          <div className="grid-2">
            <label>
              午高峰倍率
              <input type="number" min="1" step="0.1" value={form.lunchPeakMultiplier} onChange={(e) => onFieldChange('lunchPeakMultiplier', e.target.value)} />
            </label>
            <label>
              晚高峰倍率
              <input type="number" min="1" step="0.1" value={form.dinnerPeakMultiplier} onChange={(e) => onFieldChange('dinnerPeakMultiplier', e.target.value)} />
            </label>
          </div>
        </div>

        <label>
          导入配置文件
          <input type="file" accept=".json" onChange={onFileUpload} />
        </label>

        <div className="form-actions">
          <button className="primary" type="submit" disabled={loading}>
            {loading ? '运行中...' : '运行仿真'}
          </button>
          <button type="button" className="secondary" onClick={onLoadLatest} disabled={loading}>
            读取最新报告
          </button>
        </div>
        {message && <div className="message">{message}</div>}
      </form>

      <aside className="panel guide-panel">
        <h2>输入说明</h2>
        <p>当前后端最大稳定量级按 1000 名学生控制，前端会自动限制学生上限，避免一次请求造成过大的历史数据。</p>
        <ul>
          <li>同步运行接口：POST /api/simulation/run</li>
          <li>报告读取接口：GET /api/simulation/report/latest</li>
          <li>历史快照通过分页接口读取，不在页面展示原始 JSON。</li>
        </ul>
      </aside>
    </section>
  )
}

export default InputPage
