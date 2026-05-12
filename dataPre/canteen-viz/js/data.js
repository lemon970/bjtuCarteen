// ==================== 核心数据结构 ====================
export const globalData = {
    // 基础输入参数（成员D传输）
    params: {
        duration: 1.5,
        flowRate: 180,
        takeoutRatio: 0.42,
        totalSeats: 300,
        takeoutWindows: 1,
        normalWindows: 5,
        scenario: 0
    },
    // 仿真结果（成员A + 成员C）
    snapshots: [],           // 长度91的数组
    trendData: {
        time: [],
        occupancy: [],
        totalQueue: [],
        flow: []
    },
    peakOccupancy: 83,
    peakMinute: 42,
    crowdedMinutes: 23,
    crowdedPercentage: 25.6
};