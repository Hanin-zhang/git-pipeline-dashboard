import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import {
  AxisPointerComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

// Only the pieces this dashboard actually draws, so the chart bundle stays small.
echarts.use([
  BarChart,
  LineChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  AxisPointerComponent,
  CanvasRenderer,
])

export const chartTheme = {
  text: '#44506b',
  muted: '#7b869e',
  grid: '#eef1f7',
  primary: '#2f5bff',
  success: '#12a150',
  danger: '#e5484d',
  warning: '#d98200',
}

export default echarts
