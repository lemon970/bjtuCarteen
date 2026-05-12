/**
 * 更新当前时刻的所有卡片与文本
 * @param {Object} globalData - 全局数据对象
 * @param {number} currentMinute - 当前分钟
 * @param {number} currentScenario - 0 或 1
 */
export function updateUI(globalData, currentMinute, currentScenario) {
    const snap = globalData.snapshots[currentMinute] || {};

    // 时间
    const mm = String(currentMinute).padStart(2, '0');
    document.getElementById('current-time-display').textContent = `00:${mm}`;
    document.getElementById('current-minute').textContent = `（第 ${currentMinute} 分钟）`;

    // 占用座位数
    document.getElementById('occupied-seats').textContent = snap.occupiedSeats || 142;
    document.getElementById('occupied-seats-sub').innerHTML = `共 ${globalData.params.totalSeats} 个座位`;

    // 工作窗口数
    document.getElementById('active-windows').textContent = snap.activeWindows || 6;
    document.getElementById('active-windows-sub').innerHTML =
        currentScenario === 0
            ? `打包窗口 ${globalData.params.takeoutWindows} + 普通窗口 ${globalData.params.normalWindows}`
            : `普通窗口 ${globalData.params.normalWindows}`;

    // 占用率
    const rate = snap.occupancyRate || 47;
    document.getElementById('occupancy-rate').textContent = `${rate}%`;
    const statusEl = document.getElementById('occupancy-status');
    if (rate > 70) {
        statusEl.textContent = '拥挤';
        statusEl.className = 'mt-1 px-4 py-1 inline-block text-sm font-medium rounded-3xl bg-red-100 text-red-700';
    } else if (rate > 40) {
        statusEl.textContent = '正常';
        statusEl.className = 'mt-1 px-4 py-1 inline-block text-sm font-medium rounded-3xl bg-emerald-100 text-emerald-700';
    } else {
        statusEl.textContent = '宽松';
        statusEl.className = 'mt-1 px-4 py-1 inline-block text-sm font-medium rounded-3xl bg-sky-100 text-sky-700';
    }
    document.getElementById('occupancy-bar').style.width = `${rate}%`;

    // 人流量
    document.getElementById('current-flow').textContent = `${snap.footFlow || 28} 人/分钟`;

    // 平均等待时间
    document.getElementById('avg-wait').textContent = `${snap.avgWait || 4.8} min`;
}

/**
 * 更新场景切换按钮的高亮样式
 * @param {number} currentScenario
 */
// export function updateScenarioButtons(currentScenario) {
//     document.querySelectorAll('.scenario-btn').forEach((el, idx) => {
//         if (idx === currentScenario) {
//             el.classList.add('bg-[#1e40af]', 'text-white', 'border', 'border-[#1e40af]');
//             el.classList.remove('bg-white', 'text-gray-700', 'border-gray-300');
//         } else {
//             el.classList.remove('bg-[#1e40af]', 'text-white', 'border-[#1e40af]');
//             el.classList.add('bg-white', 'text-gray-700', 'border', 'border-gray-300');
//         }
//     });
// }
export function updateScenarioButtons(currentScenario) {
    document.querySelectorAll('.scenario-btn').forEach((el, idx) => {
        if (idx === currentScenario) {
            el.classList.add('bg-blue-100', 'text-[#1e40af]', 'font-semibold', 'border', 'border-[#1e40af]');
            el.classList.remove('bg-white', 'text-gray-700', 'border-gray-300');
        } else {
            el.classList.remove('bg-blue-100', 'text-[#1e40af]', 'font-semibold', 'border-[#1e40af]');
            el.classList.add('bg-white', 'text-gray-700', 'border', 'border-gray-300');
        }
    });
}

/**
 * 渲染统计报告页的参数表格与关键指标
 * @param {Object} globalData
 */
export function renderParamTable(globalData) {
    const p = globalData.params;
    const tbody = document.getElementById('param-table');
    tbody.innerHTML = `
        <tr><td class="py-5 px-8 font-medium">仿真时长</td><td class="py-5 px-8">${p.duration}</td><td class="py-5 px-8 text-gray-500">小时</td></tr>
        <tr><td class="py-5 px-8 font-medium">学生人流量</td><td class="py-5 px-8">${p.flowRate}</td><td class="py-5 px-8 text-gray-500">人/小时</td></tr>
        <tr><td class="py-5 px-8 font-medium">打包同学占比</td><td class="py-5 px-8">${(p.takeoutRatio * 100).toFixed(0)}%</td><td class="py-5 px-8 text-gray-500"></td></tr>
        <tr><td class="py-5 px-8 font-medium">食堂座位总数</td><td class="py-5 px-8">${p.totalSeats}</td><td class="py-5 px-8 text-gray-500">个</td></tr>
        <tr><td class="py-5 px-8 font-medium">打包窗口数量</td><td class="py-5 px-8">${p.takeoutWindows}</td><td class="py-5 px-8 text-gray-500">个</td></tr>
        <tr><td class="py-5 px-8 font-medium">普通窗口数量</td><td class="py-5 px-8">${p.normalWindows}</td><td class="py-5 px-8 text-gray-500">个</td></tr>
    `;

    document.getElementById('peak-occupancy').textContent = `${globalData.peakOccupancy}%`;
    document.getElementById('peak-minute').textContent = globalData.peakMinute;
    document.getElementById('crowded-time').textContent = globalData.crowdedMinutes;
    document.getElementById('crowded-percentage').textContent = `${globalData.crowdedPercentage}%`;

    const peakStatus = document.getElementById('peak-status');
    peakStatus.textContent = globalData.peakOccupancy > 70 ? '已进入拥挤状态' : '正常';
    peakStatus.className = globalData.peakOccupancy > 70
        ? 'px-4 py-1 bg-red-100 text-red-600 rounded-3xl text-sm font-medium inline-block'
        : 'px-4 py-1 bg-emerald-100 text-emerald-600 rounded-3xl text-sm font-medium inline-block';
}

/**
 * 切换 Tab 页
 * @param {number} n - 0 或 1
 * @param {Function} initCharts - 切到 Tab0 时调用的图表初始化函数
 */
export function switchTab(n, initChartsCallback) {
    // 更新 Tab 激活样式
    document.querySelectorAll('#tab-0, #tab-1').forEach(el => el.classList.remove('tab-active'));
    document.getElementById(`tab-${n}`).classList.add('tab-active');

    // 切换面板
    document.getElementById('panel-0').classList.toggle('hidden', n !== 0);
    document.getElementById('panel-1').classList.toggle('hidden', n !== 1);

    // 如果切到回放看板，重新初始化图表（避免隐藏时尺寸问题）
    if (n === 0 && initChartsCallback) {
        setTimeout(initChartsCallback, 50);
    }
}