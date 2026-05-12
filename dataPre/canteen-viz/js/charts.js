// 注意：echarts 通过 CDN 全局引入，这里直接使用
let queueChartInstance = null;
let trendChartInstance = null;

/**
 * 初始化/更新窗口排队柱状图
 * @param {Array} queueLengths - 当前分钟各窗口排队人数数组
 * @param {number} currentScenario - 0 或 1
 */
export function initQueueChart(queueLengths, currentScenario) {
    const container = document.getElementById('queue-chart');
    if (!container) return;
    if (queueChartInstance) queueChartInstance.dispose();
    queueChartInstance = echarts.init(container);

    const windowLabels = currentScenario === 0
        ? ['打包窗口', '窗口1', '窗口2', '窗口3', '窗口4', '窗口5']
        : ['窗口1', '窗口2', '窗口3', '窗口4', '窗口5', '窗口6'];

    const option = {
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
        grid: { left: '3%', right: '4%', bottom: '10%', top: '15%' },
        xAxis: { type: 'category', data: windowLabels },
        yAxis: { type: 'value', name: '排队人数' },
        series: [{
            name: '排队长度',
            type: 'bar',
            barWidth: '45%',
            data: queueLengths,
            itemStyle: {
                color: (p) => p.dataIndex === 0 && currentScenario === 0 ? '#f59e0b' : '#1e40af'
            }
        }]
    };
    queueChartInstance.setOption(option);
}

/**
 * 初始化全局趋势折线图
 * @param {Object} trendData - 包含 time, occupancy, totalQueue, flow 数组
 */
export function initTrendChart(trendData) {
    const container = document.getElementById('trend-chart');
    if (!container) return;
    if (trendChartInstance) trendChartInstance.dispose();
    trendChartInstance = echarts.init(container);

    const option = {
        tooltip: { trigger: 'axis' },
        legend: { data: ['空间占用率', '总排队人数', '人流量'], bottom: 0 },
        xAxis: { type: 'category', data: trendData.time.map(t => t + 'min') },
        yAxis: [
            { type: 'value', name: '占用率(%)' },
            { type: 'value', name: '人数' }
        ],
        series: [
            { name: '空间占用率', type: 'line', smooth: true, data: trendData.occupancy, color: '#1e40af' },
            { name: '总排队人数', type: 'line', smooth: true, data: trendData.totalQueue, yAxisIndex: 1, color: '#f59e0b' },
            { name: '人流量', type: 'line', smooth: true, data: trendData.flow, yAxisIndex: 1, color: '#10b981' }
        ]
    };
    trendChartInstance.setOption(option);
}

/**
 * 仅更新柱状图的数据（不重新创建实例）
 */
export function updateQueueChart(queueLengths, currentScenario) {
    if (!queueChartInstance) return;
    const windowLabels = currentScenario === 0
        ? ['打包窗口', '窗口1', '窗口2', '窗口3', '窗口4', '窗口5']
        : ['窗口1', '窗口2', '窗口3', '窗口4', '窗口5', '窗口6'];
    queueChartInstance.setOption({
        xAxis: { data: windowLabels },
        series: [{ data: queueLengths }]
    });
}