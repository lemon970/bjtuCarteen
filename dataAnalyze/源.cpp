#include <iostream>
#include "DiningSimulation.h"

using namespace std;

int main() {
    cout << "========================================" << endl;
    cout << "    北京交通大学就餐仿真系统" << endl;
    cout << "    API v1.0.0 Compatible" << endl;
    cout << "========================================" << endl;

    DiningSimulation simulation;

    // 配置仿真参数（符合API规范）
    SimulationConfig config;
    config.simulationName = "test-simulation";
    config.duration = 1.0;                    // 1小时
    config.arrivalRate = 60.0;                // 60人/小时
    config.queueLimit = 10;                   // 窗口排队上限10人
    config.packProbability = 0.2;             // 20%打包概率
    config.seed = 42;                         // 固定随机种子

    config.baseConfig.windowCount = 4;
    config.baseConfig.totalSeats = 40;
    config.baseConfig.totalStudents = 0;      // 由arrivalRate推导

    config.weatherConfig.currentWeather = "sunny";
    config.weatherConfig.weatherImpactFactor = 1.0;

    config.randomBounds.arrivalInterval = 0;  // 由arrivalRate推导
    config.randomBounds.serviceRange = { 60, 180 };    // 1-3分钟
    config.randomBounds.diningRange = { 600, 1200 };   // 10-20分钟
    config.randomBounds.preferenceRange = { 0.1, 0.3 };

    // 应用配置
    simulation.configure(config);

    // 运行仿真
    cout << "\n运行仿真..." << endl;
    simulation.runSimulation();

    // 获取报告
    SimulationReport report = simulation.getReport();

    // 打印报告
    simulation.printReport();

    // 导出JSON
    string jsonFile = "simulation_report_" + report.metadata.reportId + ".json";
    simulation.exportToJson(jsonFile);

    // 输出事件历史摘要
    cout << "\n【事件历史摘要】" << endl;
    cout << "总事件数: " << report.summary.history.size() << endl;
    int eventCount = 0;
    for (const auto& event : report.summary.history) {
        if (eventCount++ >= 20) {
            cout << "... 还有 " << (report.summary.history.size() - 20) << " 个事件" << endl;
            break;
        }
        cout << "[" << event.time << "s] " << event.eventMessage << endl;
    }

    // 输出时间线摘要
    cout << "\n【时间线摘要】" << endl;
    cout << "快照数量: " << report.summary.timeline.size() << endl;
    for (const auto& snapshot : report.summary.timeline) {
        if (snapshot.minute % 10 == 0) {
            cout << "分钟 " << snapshot.minute << ": "
                << "队列=" << snapshot.totalQueueSize
                << ", 座位占用=" << snapshot.occupiedSeats
                << ", 累计到达=" << snapshot.cumulativeArrivedCount << endl;
        }
    }

    return 0;
}