export const API_BASE = '/api/simulation'
export const MAX_STUDENTS = 1000
export const MAX_SEATS = 1000
export const MAX_RENDERED_SEATS = 360
export const HISTORY_PAGE_SIZE = 20

export const ROUTES = [
  { key: 'input', label: '信息输入', description: '选择模型并调整参数' },
  { key: 'display', label: '数据展示', description: '查看运行过程和座位状态' },
  { key: 'analysis', label: '模型分析', description: '解释等待、打包和异常原因' }
]

export const DEFAULT_FORM = {
  simulationName: '午高峰压力测试',
  duration: 2.0,
  arrivalRate: 300,
  queueLimit: 40,
  packProbability: 0.15,
  seed: 20260512,
  windowCount: 8,
  takeawayWindowCount: 1,
  takeawayServiceTimeMultiplier: 1.2,
  totalSeats: 250,
  totalStudents: 1000,
  arrivalInterval: 0,
  arrivalLambda: 300,
  serviceMean: 90,
  diningMean: 1500,
  peakEnabled: true,
  lunchPeakStart: 12,
  lunchPeakEnd: 32,
  lunchPeakMultiplier: 2.6,
  dinnerPeakStart: 64,
  dinnerPeakEnd: 86,
  dinnerPeakMultiplier: 1.8,
  serviceMin: 45,
  serviceMax: 180,
  diningMin: 900,
  diningMax: 2400,
  preferenceMin: 0.05,
  preferenceMax: 0.2,
  currentWeather: 'sunny',
  weatherImpactFactor: 1.0,
  groupArrivalProb: 0.08,
  partySize: 3,
  groupEnabled: false,
  groupCount: 20,
  groupSizeMin: 3,
  groupSizeMax: 4,
  groupArrivalSpreadSeconds: 0,
  groupBehaviorCorrelation: 0.75,
  preferAdjacentSeats: true,
  walkTimeMean: 8,
  congestionPenalty: 0.35
}
