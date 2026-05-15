import { useEffect, useRef } from 'react'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  MarkPointComponent,
  TitleComponent,
  TooltipComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

let registered = false

function ensureRegistered() {
  if (registered) return
  echarts.use([
    LineChart,
    BarChart,
    GridComponent,
    TitleComponent,
    TooltipComponent,
    LegendComponent,
    MarkLineComponent,
    MarkPointComponent,
    CanvasRenderer
  ])
  registered = true
}

export function useEcharts(option, deps = []) {
  const containerRef = useRef(null)
  const instanceRef = useRef(null)

  useEffect(() => {
    ensureRegistered()
    const node = containerRef.current
    if (!node) return undefined
    const chart = echarts.init(node)
    instanceRef.current = chart
    const handleResize = () => chart.resize()
    const observer = new ResizeObserver(handleResize)
    observer.observe(node)
    window.addEventListener('resize', handleResize)
    return () => {
      observer.disconnect()
      window.removeEventListener('resize', handleResize)
      chart.dispose()
      instanceRef.current = null
    }
  }, [])

  useEffect(() => {
    const chart = instanceRef.current
    if (!chart) return
    chart.setOption(option, true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  return containerRef
}
