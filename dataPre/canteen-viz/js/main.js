import { globalData } from './data.js';
import { initQueueChart, initTrendChart, updateQueueChart } from './charts.js';
import {
    updateUI,
    updateScenarioButtons,
    renderParamTable,
    switchTab
} from './ui.js';

// ==================== 播放状态 ====================
let currentScenario = 0;
let isPlaying = false;
let currentMinute = 0;
let totalMinutes = 90;
let timer = null;

// ==================== 数据接收 ====================
function receiveSimulationData(backendJson) {
    console.log('%c📥 接收到后端完整数据包', 'color:#1e40af; font-weight:700');

    // 更新基础参数
    if (backendJson.params) {
        Object.assign(globalData.params, backendJson.params);
    }

    // 更新快照
    if (backendJson.snapshots && Array.isArray(backendJson.snapshots)) {
        globalData.snapshots = backendJson.snapshots;
        totalMinutes = globalData.snapshots.length - 1;
        document.getElementById('timeline').max = totalMinutes;
    }

    // 更新趋势数据与统计结果
    if (backendJson.trendData) globalData.trendData = backendJson.trendData;
    if (backendJson.peakOccupancy !== undefined) globalData.peakOccupancy = backendJson.peakOccupancy;
    if (backendJson.peakMinute !== undefined) globalData.peakMinute = backendJson.peakMinute;
    if (backendJson.crowdedMinutes !== undefined) globalData.crowdedMinutes = backendJson.crowdedMinutes;
    if (backendJson.crowdedPercentage !== undefined) globalData.crowdedPercentage = backendJson.crowdedPercentage;

    // 自动切换场景
    currentScenario = globalData.params.scenario || 0;
    updateScenarioButtons(currentScenario);

    // 刷新数据接收状态
    const statusEl = document.getElementById('data-status');
    // statusEl.classList.add('bg-emerald-100', 'text-emerald-700');
    statusEl.classList.add('bg-emerald-100', 'text-emerald-700', 'border', 'border-emerald-300');
    document.getElementById('receive-text').innerHTML = ` 已接收 • ${globalData.snapshots.length}个快照`;

    // 如果当前在回放看板，重绘图表
    if (!document.getElementById('panel-0').classList.contains('hidden')) {
        initAllCharts();
    }
    updateUI(globalData, currentMinute, currentScenario);
    renderParamTable(globalData);

    console.log('✅ 数据已成功注入全局变量 globalData，可直接用于ECharts渲染');
}

// ==================== 演示数据加载 ====================
function loadDemoData() {
    const demoBackendJson = {
        params: {
            duration: 1.5,
            flowRate: 180,
            takeoutRatio: 0.42,
            totalSeats: 300,
            takeoutWindows: currentScenario === 0 ? 1 : 0,
            normalWindows: currentScenario === 0 ? 5 : 6,
            scenario: currentScenario
        },
        snapshots: Array.from({ length: 91 }, (_, i) => ({
            minute: i,
            queueLengths: currentScenario === 0
                ? [Math.max(0, Math.floor(6 + Math.sin(i / 8) * 6)), ...Array(5).fill().map(() => Math.max(0, Math.floor(10 + Math.random() * 18)))]
                : Array(6).fill().map(() => Math.max(0, Math.floor(15 + Math.random() * 22))),
            occupiedSeats: Math.floor(110 + Math.sin(i / 9) * 95),
            totalSeats: 300,
            occupancyRate: Math.floor(32 + Math.random() * 58),
            activeWindows: currentScenario === 0 ? 6 : 6,
            footFlow: Math.floor(14 + Math.random() * 28),
            avgWait: (2.8 + Math.random() * 3.5).toFixed(1)
        })),
        trendData: {
            time: Array.from({ length: 91 }, (_, i) => i),
            occupancy: Array.from({ length: 91 }, (_, i) => Math.max(28, Math.floor(38 + Math.sin(i / 11) * 38))),
            totalQueue: Array.from({ length: 91 }, (_, i) => Math.floor(40 + Math.random() * 45)),
            flow: Array.from({ length: 91 }, (_, i) => Math.floor(18 + Math.sin(i / 14) * 16))
        },
        peakOccupancy: currentScenario === 0 ? 83 : 91,
        peakMinute: currentScenario === 0 ? 42 : 38,
        crowdedMinutes: currentScenario === 0 ? 23 : 31,
        crowdedPercentage: currentScenario === 0 ? 25.6 : 34.4
    };

    receiveSimulationData(demoBackendJson);
    hideReceivePanel();
}

// 生产模式占位
function receiveRealData() {
    alert('🚀 生产模式：已调用后端API接口 /api/simulation/result\n（请在真实项目中实现 fetch + receiveSimulationData）');
    // fetch('/api/simulation/result', { method: 'POST', body: JSON.stringify({requestId: 'xxx'}) })
    //   .then(r => r.json())
    //   .then(data => receiveSimulationData(data));
    loadDemoData();
}

// ==================== 时间轴与播放控制 ====================
function updateFromSlider() {
    currentMinute = parseInt(document.getElementById('timeline').value, 10);
    updateUI(globalData, currentMinute, currentScenario);
    const snap = globalData.snapshots[currentMinute];
    if (snap && snap.queueLengths) {
        updateQueueChart(snap.queueLengths, currentScenario);
    }
}

function playPause() {
    const btn = document.getElementById('play-btn');
    if (isPlaying) {
        clearInterval(timer);
        btn.innerHTML = `▶️ 播放`;
        isPlaying = false;
    } else {
        timer = setInterval(() => {
            currentMinute = Math.min(currentMinute + 1, totalMinutes);
            document.getElementById('timeline').value = currentMinute;
            updateUI(globalData, currentMinute, currentScenario);
            const snap = globalData.snapshots[currentMinute];
            if (snap && snap.queueLengths) {
                updateQueueChart(snap.queueLengths, currentScenario);
            }
            if (currentMinute >= totalMinutes) {
                clearInterval(timer);
                isPlaying = false;
                btn.innerHTML = `🔄 重新播放`;
            }
        }, 80);
        btn.innerHTML = `⏸️ 暂停`;
        isPlaying = true;
    }
}

function resetTimeline() {
    clearInterval(timer);
    isPlaying = false;
    currentMinute = 0;
    document.getElementById('timeline').value = 0;
    document.getElementById('play-btn').innerHTML = `▶️ 播放`;
    updateUI(globalData, currentMinute, currentScenario);
    const snap = globalData.snapshots[0];
    if (snap && snap.queueLengths) {
        updateQueueChart(snap.queueLengths, currentScenario);
    }
}

// ==================== 场景切换 ====================
function switchScenarioHandler(n) {
    currentScenario = n;
    globalData.params.scenario = n;
    globalData.params.takeoutWindows = n === 0 ? 1 : 0;
    globalData.params.normalWindows = n === 0 ? 5 : 6;
    updateScenarioButtons(currentScenario);
    loadDemoData(); // 切换场景后自动刷新演示数据（后期联调可替换）
}

// ==================== 面板显示/隐藏 ====================
function showReceivePanel() {
    document.getElementById('receive-panel').classList.remove('hidden');
}
function hideReceivePanel() {
    document.getElementById('receive-panel').classList.add('hidden');
}

// 占位的 legend 切换（可后续实现）
function toggleLegend() {
    console.log('图例切换');
}

// ==================== 初始化所有图表 ====================
function initAllCharts() {
    const snap = globalData.snapshots[currentMinute];
    if (snap && snap.queueLengths) {
        initQueueChart(snap.queueLengths, currentScenario);
    }
    initTrendChart(globalData.trendData);
}

// ==================== 页面加载完成后绑定事件并启动 ====================
window.onload = () => {
    // 初始加载演示数据
    loadDemoData();

    // 默认显示 Tab0
    switchTab(0, initAllCharts);

    // 绑定场景切换按钮
    document.getElementById('scenario-0').addEventListener('click', () => switchScenarioHandler(0));
    document.getElementById('scenario-1').addEventListener('click', () => switchScenarioHandler(1));

    // Tab 切换
    document.getElementById('tab-0').addEventListener('click', () => {
        switchTab(0, initAllCharts);
        updateUI(globalData, currentMinute, currentScenario);
    });
    document.getElementById('tab-1').addEventListener('click', () => {
        switchTab(1);
        renderParamTable(globalData);
    });

    // 数据接收面板
    document.getElementById('data-status').addEventListener('click', showReceivePanel);
    document.getElementById('hide-receive-panel-btn').addEventListener('click', hideReceivePanel);

    // 测试/正式接收按钮
    document.getElementById('load-demo-btn').addEventListener('click', loadDemoData);
    document.getElementById('receive-real-btn').addEventListener('click', receiveRealData);

    // 时间轴控制
    document.getElementById('timeline').addEventListener('input', updateFromSlider);
    document.getElementById('play-btn').addEventListener('click', playPause);
    document.getElementById('reset-btn').addEventListener('click', resetTimeline);

    // 图例按钮（可选）
    document.getElementById('toggle-legend-btn').addEventListener('click', toggleLegend);

    console.log('%c✅ 食堂仿真前端工程化版本已就绪！', 'color:#1e40af; font-size:16px; font-weight:700');
};