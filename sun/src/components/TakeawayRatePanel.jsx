import { useMemo } from 'react'

import { formatNumber, formatPercent, read, toNumber } from '../utils/simulation'
import { useEcharts } from '../utils/useEcharts'

function TakeawayRatePanel({ summary, config }) {
  const actualRate = toNumber(read(summary, 'takeaway_rate', 'takeawayRate'), 0)
  const rawPack = toNumber(read(config || {}, 'pack_probability', 'packProbability'), 0)
  const theoretical = toNumber(read(summary, 'theoretical_takeaway_rate', 'theoreticalTakeawayRate'), null)
  const baseRate = theoretical != null && theoretical > 0 ? theoretical : rawPack
  const breakdown = read(summary, 'takeaway_rate_breakdown', 'takeawayRateBreakdown') || {}
  const initialIntentRate = toNumber(read(breakdown, 'initial_intent_rate', 'initialIntentRate'), 0)
  const dynamicFlipRate = toNumber(read(breakdown, 'dynamic_flip_rate', 'dynamicFlipRate'), 0)
  const noSeatForcedRate = toNumber(read(breakdown, 'no_seat_forced_rate', 'noSeatForcedRate'), 0)
  const takeawayCount = toNumber(read(summary, 'takeaway_count', 'takeawayCount'), 0)
  const servedCount = toNumber(read(summary, 'served_count', 'servedCount'), 0)
  const takeawayWindowServed = toNumber(read(summary, 'takeaway_window_served_count', 'takeawayWindowServedCount'), 0)
  const weatherDriven = toNumber(read(summary, 'weather_driven_takeaway_count', 'weatherDrivenTakeawayCount'), 0)
  const noSeatSwitch = toNumber(read(summary, 'no_seat_switch_to_takeaway_count', 'noSeatSwitchToTakeawayCount'), 0)
  const noSeatAbandoned = toNumber(read(summary, 'no_seat_abandoned_count', 'noSeatAbandonedCount'), 0)
  const noSeatAbandonedRate = toNumber(read(summary, 'no_seat_abandoned_rate', 'noSeatAbandonedRate'), 0)
  const timeline = Array.isArray(read(summary, 'timeline')) ? read(summary, 'timeline') : []

  const deviation = baseRate > 0 ? (actualRate - baseRate) / baseRate : 0
  const absDeviation = Math.abs(deviation)
  const status = absDeviation < 0.15 ? 'normal' : absDeviation < 0.30 ? 'warning' : 'critical'
  const accentClass = status === 'critical' ? 'text-semantic-critical' : status === 'warning' ? 'text-accent-amber' : 'text-bjtu-700'
  const badgeClass = status === 'critical'
    ? 'bg-semantic-critical/10 text-semantic-critical'
    : status === 'warning'
      ? 'bg-accent-amber/10 text-accent-amber'
      : 'bg-bjtu-50 text-bjtu-700'
  const badgeText = deviation > 0 ? `高于基准 ${formatPercent(absDeviation)}` : deviation < 0 ? `低于基准 ${formatPercent(absDeviation)}` : '与基准一致'

  const sparkOption = useMemo(() => {
    const points = []
    for (const point of timeline) {
      const minute = toNumber(read(point, 'minute'), 0)
      const arrived = toNumber(read(point, 'cumulative_arrived_count', 'cumulativeArrivedCount', 'arrived_count', 'arrivedCount'), 0)
      const served = toNumber(read(point, 'cumulative_served_count', 'cumulativeServedCount', 'served_count', 'servedCount'), 0)
      const takeaway = toNumber(read(point, 'cumulative_takeaway_count', 'cumulativeTakeawayCount', 'takeaway_count', 'takeawayCount'), 0)
      const denom = served > 0 ? served : arrived
      const rate = denom > 0 ? takeaway / denom : 0
      points.push([minute, rate])
    }
    const yMax = Math.max(0.6, Math.max(baseRate, actualRate) * 1.5)
    return {
      grid: { left: 0, right: 0, top: 4, bottom: 4 },
      tooltip: {
        trigger: 'axis',
        formatter: (params) => {
          const item = params[0]
          return `第 ${item.value[0]} 分钟<br/>累计打包率 ${(item.value[1] * 100).toFixed(1)}%`
        }
      },
      xAxis: { type: 'value', show: false, min: 'dataMin', max: 'dataMax' },
      yAxis: { type: 'value', show: false, min: 0, max: yMax },
      series: [
        {
          type: 'line',
          smooth: true,
          showSymbol: false,
          data: points,
          areaStyle: { color: status === 'critical' ? '#fee2e2' : status === 'warning' ? '#fef3c7' : '#dbeafe' },
          lineStyle: { color: status === 'critical' ? '#dc2626' : status === 'warning' ? '#f59e0b' : '#1e40af', width: 2 }
        },
        {
          type: 'line',
          showSymbol: false,
          markLine: {
            silent: true,
            symbol: 'none',
            label: { show: false },
            lineStyle: { color: '#94a3b8', type: 'dashed', width: 1 },
            data: [{ yAxis: baseRate }]
          },
          data: []
        }
      ]
    }
  }, [timeline, baseRate, actualRate, status])

  const sparkRef = useEcharts(sparkOption, [sparkOption])

  return (
    <section className="panel">
      <div className="panel-title">
        <div>
          <h2>打包率主面板</h2>
          <p>实际打包率 vs 理论基准(packProbability × weatherFactor),偏离度超过 15% 转黄,超过 30% 转红。</p>
        </div>
        <span className={`rounded-full px-3 py-1 text-xs font-medium ${badgeClass}`}>{badgeText}</span>
      </div>

      <div className="mt-3 grid grid-cols-1 gap-4 lg:grid-cols-[3fr_2fr]">
        <div className="rounded-2xl border border-canvas-border bg-canvas-surface p-5">
          <p className="field-label">当前实际打包率</p>
          <p className={`mt-2 text-5xl font-semibold ${accentClass}`}>{formatPercent(actualRate, 1)}</p>
          <p className="mt-2 text-sm text-slate-500">
            打包 <strong className="text-slate-700">{takeawayCount}</strong> 人 / 已服务 <strong className="text-slate-700">{servedCount}</strong> 人
            {takeawayWindowServed > 0 && ` · 打包窗口服务 ${takeawayWindowServed} 人`}
          </p>
          <div className="mt-4 grid grid-cols-3 gap-3 text-xs">
            <Fact
              label="基准(已含天气)"
              value={formatPercent(baseRate, 1)}
              hint={theoretical != null ? `= packProb ${formatPercent(rawPack, 1)} × weatherFactor` : 'config 输入(无 weather 调整)'}
            />
            <Fact label="天气驱动打包" value={weatherDriven} hint="rainy/snowy 偏好抬升" />
            <Fact label="无座离开" value={`${noSeatAbandoned} (${formatPercent(noSeatAbandonedRate, 1)})`} hint="想堂食但无座,不计打包" />
          </div>
          <div className="mt-3 grid grid-cols-3 gap-3 text-xs">
            <Fact label="初始意图占比" value={formatPercent(initialIntentRate, 1)} hint="到达即决定打包" />
            <Fact label="动态翻转占比" value={formatPercent(dynamicFlipRate, 1)} hint={`完成服务后翻转 ${weatherDriven}`} />
            <Fact label="无座强制占比" value={formatPercent(noSeatForcedRate, 1)} hint={`堂食意图无座 ${noSeatSwitch}`} />
          </div>
        </div>
        <div className="rounded-2xl border border-canvas-border bg-canvas-surface p-5">
          <div className="flex items-center justify-between">
            <p className="field-label">累计打包率走势</p>
            <span className="text-xs text-slate-400">虚线 = 理论基准</span>
          </div>
          <div ref={sparkRef} className="mt-3 h-24 w-full" />
          <p className="mt-2 text-xs text-slate-500">
            偏离 {deviation >= 0 ? '+' : ''}{formatNumber(deviation * 100, 1)}% · 状态 {status === 'critical' ? '异常' : status === 'warning' ? '关注' : '正常'}
          </p>
        </div>
      </div>
    </section>
  )
}

function Fact({ label, value, hint }) {
  return (
    <div className="rounded-lg bg-canvas-base p-2">
      <p className="text-[11px] text-slate-500">{label}</p>
      <p className="mt-1 text-base font-semibold text-slate-700">{value}</p>
      <p className="mt-0.5 text-[10px] text-slate-400">{hint}</p>
    </div>
  )
}

export default TakeawayRatePanel

