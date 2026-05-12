// [重构] 集中维护默认表单值和路由，避免三类页面重复定义同一批常量。
export const API_BASE = '/api/simulation'
export const MAX_STUDENTS = 1000
export const HISTORY_PAGE_SIZE = 20

export const ROUTES = [
  { key: 'input', label: '信息输入', description: '设置流量、座位与概率模型' },
  { key: 'display', label: '实时展示', description: '查看人流、座位热力与指标' },
  { key: 'analysis', label: '模型分析', description: '验证分布、打包与拥挤风险' }
]

export const DEFAULT_FORM = {
  simulationName: 'canteen-simulation',
  duration: 1.0,
  arrivalRate: 180,
  queueLimit: 10,
  packProbability: 0.2,
  seed: 12345,
  windowCount: 5,
  takeawayWindowCount: 1,
  takeawayServiceTimeMultiplier: 1.15,
  totalSeats: 120,
  totalStudents: 1000,
  arrivalInterval: 0,
  arrivalLambda: 180,
  serviceMean: 120,
  diningMean: 900,
  peakEnabled: true,
  lunchPeakStart: 12,
  lunchPeakEnd: 24,
  lunchPeakMultiplier: 3.2,
  dinnerPeakStart: 34,
  dinnerPeakEnd: 48,
  dinnerPeakMultiplier: 2.4,
  serviceMin: 60,
  serviceMax: 180,
  diningMin: 600,
  diningMax: 1200,
  preferenceMin: 0.1,
  preferenceMax: 0.3,
  currentWeather: 'sunny',
  weatherImpactFactor: 1.0,
  groupArrivalProb: 0,
  partySize: 1,
  walkTimeMean: 0,
  congestionPenalty: 0
}
