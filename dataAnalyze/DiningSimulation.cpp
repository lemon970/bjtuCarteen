#define _CRT_SECURE_NO_WARNINGS
#include "DiningSimulation.h"
#include <iostream>
#include <iomanip>
#include <fstream>
#include <cmath>
#include <algorithm>
#include <numeric>
#include <sstream>
#include <chrono>
#include <random>

using namespace std;

DiningSimulation::DiningSimulation() : rng(random_device{}()) {
    arrivedCount = 0;
    abandonedCount = 0;
    servedCount = 0;
    dineInCount = 0;
    takeawayCount = 0;
    leaveCount = 0;
    totalWaitTimeSeconds = 0.0;

    maxQueueSize = 0;
    peakTimeMinutes = 0;
    peakWindowId = -1;
    maxTotalQueueSize = 0;
    maxOccupiedSeats = 0;
}

void DiningSimulation::normalizeConfig() {
    // 规范化 serviceRange
    if (config.randomBounds.serviceRange.size() >= 2) {
        config.effectiveServiceMin = max(1, config.randomBounds.serviceRange[0]);
        config.effectiveServiceMax = max(config.effectiveServiceMin + 1,
            config.randomBounds.serviceRange[1]);
    }
    else {
        config.effectiveServiceMin = 60;
        config.effectiveServiceMax = 300;
    }

    // 规范化 diningRange
    if (config.randomBounds.diningRange.size() >= 2) {
        config.effectiveDiningMin = max(1, config.randomBounds.diningRange[0]);
        config.effectiveDiningMax = max(config.effectiveDiningMin + 1,
            config.randomBounds.diningRange[1]);
    }
    else {
        config.effectiveDiningMin = 600;
        config.effectiveDiningMax = 1800;
    }

    // 规范化 arrivalInterval
    if (config.randomBounds.arrivalInterval <= 0 && config.arrivalRate > 0) {
        config.effectiveArrivalInterval = static_cast<int>(round(3600.0 / config.arrivalRate));
    }
    else if (config.randomBounds.arrivalInterval > 0) {
        config.effectiveArrivalInterval = config.randomBounds.arrivalInterval;
    }
    else {
        config.effectiveArrivalInterval = 60;
    }

    // 确保到达间隔至少为1秒
    if (config.effectiveArrivalInterval < 1) {
        config.effectiveArrivalInterval = 1;
    }

    // 规范化 totalStudents
    if (config.baseConfig.totalStudents <= 0 && config.arrivalRate > 0) {
        double durationSeconds = config.duration * 3600.0;
        config.effectiveTotalStudents = static_cast<int>(floor(durationSeconds / config.effectiveArrivalInterval));
    }
    else {
        config.effectiveTotalStudents = config.baseConfig.totalStudents;
    }

    // 限制学生数量避免过大
    if (config.effectiveTotalStudents > 10000) {
        config.effectiveTotalStudents = 10000;
    }

    // 确保窗口数量有效
    if (config.baseConfig.windowCount <= 0) {
        config.baseConfig.windowCount = 1;
    }

    // 确保座位数量有效
    if (config.baseConfig.totalSeats < 0) {
        config.baseConfig.totalSeats = 0;
    }
}

void DiningSimulation::configure(const SimulationConfig& cfg) {
    config = cfg;
    normalizeConfig();

    // 设置随机种子
    if (config.seed != 0) {
        rng.seed(static_cast<unsigned>(config.seed));
    }
    else {
        rng.seed(static_cast<unsigned>(chrono::steady_clock::now().time_since_epoch().count()));
        config.seed = rng();
    }
}

void DiningSimulation::initializeSimulation() {
    // 重置统计
    arrivedCount = 0;
    abandonedCount = 0;
    servedCount = 0;
    dineInCount = 0;
    takeawayCount = 0;
    leaveCount = 0;
    totalWaitTimeSeconds = 0.0;

    maxQueueSize = 0;
    peakTimeMinutes = 0;
    peakWindowId = -1;
    maxTotalQueueSize = 0;
    maxOccupiedSeats = 0;
    totalQueueSizeHistory.clear();
    occupiedSeatsHistory.clear();
    timelineSnapshots.clear();
    historyEvents.clear();

    // 初始化窗口
    windows.clear();
    for (int i = 0; i < config.baseConfig.windowCount; i++) {
        windows.push_back(Window(i));
    }
}

void DiningSimulation::generateArrivals() {
    students.clear();

    int totalStudents = config.effectiveTotalStudents;
    if (totalStudents <= 0) {
        // 如果没有学生，添加一个占位符避免空数组
        totalStudents = 1;
    }

    students.resize(totalStudents);

    // 确保分布范围有效
    int serviceMin = max(1, config.effectiveServiceMin);
    int serviceMax = max(serviceMin + 1, config.effectiveServiceMax);
    int diningMin = max(1, config.effectiveDiningMin);
    int diningMax = max(diningMin + 1, config.effectiveDiningMax);
    int windowCount = max(1, config.baseConfig.windowCount);

    // 使用均匀分布
    uniform_int_distribution<int> serviceDist(serviceMin, serviceMax);
    uniform_int_distribution<int> diningDist(diningMin, diningMax);
    uniform_real_distribution<double> packDist(0.0, 1.0);
    uniform_int_distribution<int> windowDist(0, windowCount - 1);

    double currentTime = 0;
    for (int i = 0; i < totalStudents && i < 10000; i++) {
        students[i].id = i;
        students[i].arrivalTime = currentTime;

        // 打包决策（考虑天气影响）
        double effectivePackProb = config.packProbability * config.weatherConfig.weatherImpactFactor;
        effectivePackProb = max(0.0, min(1.0, effectivePackProb));  // 限制在[0,1]
        students[i].isTakeaway = (packDist(rng) < effectivePackProb);

        // 随机生成服务时间
        students[i].serviceTime = static_cast<double>(serviceDist(rng));

        // 随机生成就餐时间
        students[i].diningTime = static_cast<double>(diningDist(rng));

        // 随机选择窗口
        students[i].targetWindow = windowDist(rng);

        students[i].isAbandon = false;
        students[i].waitTime = 0;
        students[i].finishTime = 0;
        students[i].leaveTime = 0;

        // 下一个到达时间
        currentTime += config.effectiveArrivalInterval;
    }
}

void DiningSimulation::recordHistoryEvent(int time, const string& message,
    int arrivedDelta, int abandonedDelta,
    int servedDelta, int dineInDelta,
    int takeawayDelta, int leaveDelta) {
    HistoryEvent event;
    event.time = time;
    event.eventMessage = message;

    // 记录当前队列状态
    for (const auto& window : windows) {
        event.queueSizes.push_back(window.queueLength);
    }
    event.totalQueueSize = 0;
    for (int qs : event.queueSizes) {
        event.totalQueueSize += qs;
    }

    // 更新累计计数
    arrivedCount += arrivedDelta;
    abandonedCount += abandonedDelta;
    servedCount += servedDelta;
    dineInCount += dineInDelta;
    takeawayCount += takeawayDelta;
    leaveCount += leaveDelta;

    event.arrivedCount = arrivedCount;
    event.abandonedCount = abandonedCount;
    event.servedCount = servedCount;
    event.dineInCount = dineInCount;
    event.takeawayCount = takeawayCount;
    event.leaveCount = leaveCount;

    // 座位状态
    int minuteIdx = time / 60;
    int occupied = 0;
    if (minuteIdx >= 0 && minuteIdx < (int)occupiedSeatsHistory.size()) {
        occupied = occupiedSeatsHistory[minuteIdx];
    }
    event.occupiedSeats = occupied;
    event.emptySeats = max(0, config.baseConfig.totalSeats - occupied);

    historyEvents.push_back(event);
}

void DiningSimulation::updateQueuePeaks(int minute) {
    int totalQueue = 0;
    for (const auto& window : windows) {
        totalQueue += window.queueLength;
        if (window.queueLength > maxQueueSize) {
            maxQueueSize = window.queueLength;
            peakTimeMinutes = minute;
            peakWindowId = window.windowId;
        }
    }

    if (totalQueue > maxTotalQueueSize) {
        maxTotalQueueSize = totalQueue;
    }
    totalQueueSizeHistory.push_back(totalQueue);
}

void DiningSimulation::updateSeatStats(int minute) {
    // 计算当前占用座位数
    int occupied = 0;
    for (const auto& student : students) {
        if (student.isAbandon) continue;
        if (student.isTakeaway) continue;
        int finishMinute = static_cast<int>(student.finishTime / 60);
        int leaveMinute = static_cast<int>(student.leaveTime / 60);
        if (finishMinute <= minute && minute < leaveMinute && student.leaveTime > 0) {
            occupied++;
        }
    }
    occupied = min(occupied, config.baseConfig.totalSeats);
    occupiedSeatsHistory.push_back(occupied);

    if (occupied > maxOccupiedSeats) {
        maxOccupiedSeats = occupied;
    }
}

void DiningSimulation::takeMinuteSnapshot(int minute) {
    MinuteSnapshot snapshot;
    snapshot.minute = minute;

    // 窗口队列状态
    for (const auto& window : windows) {
        snapshot.windowQueueSizes.push_back(window.queueLength);
    }
    snapshot.totalQueueSize = 0;
    for (int qs : snapshot.windowQueueSizes) {
        snapshot.totalQueueSize += qs;
    }

    // 座位状态
    int occupied = 0;
    if (minute >= 0 && minute < (int)occupiedSeatsHistory.size()) {
        occupied = occupiedSeatsHistory[minute];
    }
    snapshot.occupiedSeats = occupied;
    snapshot.emptySeats = max(0, config.baseConfig.totalSeats - occupied);

    // 累计计数
    snapshot.cumulativeArrivedCount = arrivedCount;
    snapshot.cumulativeAbandonedCount = abandonedCount;
    snapshot.cumulativeServedCount = servedCount;
    snapshot.cumulativeDineInCount = dineInCount;
    snapshot.cumulativeTakeawayCount = takeawayCount;
    snapshot.cumulativeLeaveCount = leaveCount;

    timelineSnapshots[minute] = snapshot;
}

void DiningSimulation::processArrivals() {
    int totalMinutes = static_cast<int>(ceil(config.duration * 60));
    if (totalMinutes <= 0) totalMinutes = 1;

    // 先记录初始快照
    updateQueuePeaks(0);
    updateSeatStats(0);
    takeMinuteSnapshot(0);

    // 处理每个学生的到达和服务
    for (auto& student : students) {
        int arrivalMinute = static_cast<int>(student.arrivalTime / 60);

        // 到达事件
        string arriveMsg = "student-" + to_string(student.id + 1) +
            " arrived and queued at window " + to_string(student.targetWindow);
        recordHistoryEvent(static_cast<int>(student.arrivalTime), arriveMsg, 1, 0, 0, 0, 0, 0);

        // 放弃判断：检查目标窗口队列是否超过限制
        if (student.targetWindow >= 0 && student.targetWindow < (int)windows.size()) {
            Window& targetWindow = windows[student.targetWindow];
            if (config.queueLimit > 0 && targetWindow.queueLength >= config.queueLimit) {
                student.isAbandon = true;
                recordHistoryEvent(static_cast<int>(student.arrivalTime),
                    "student-" + to_string(student.id + 1) + " abandoned: queue limit reached",
                    0, 1, 0, 0, 0, 0);
            }
            else {
                // 加入队列
                targetWindow.queue.push(student.id);
                targetWindow.queueLength++;
            }
        }
    }

    // 按时间顺序处理服务
    for (int minute = 0; minute <= totalMinutes; minute++) {
        // 处理服务完成事件
        for (auto& window : windows) {
            while (!window.queue.empty()) {
                int studentId = window.queue.front();
                if (studentId < 0 || studentId >= (int)students.size()) {
                    window.queue.pop();
                    window.queueLength--;
                    continue;
                }
                Student& student = students[studentId];
                int serviceFinishMinute = static_cast<int>(
                    (student.arrivalTime + student.serviceTime) / 60
                    );

                if (serviceFinishMinute <= minute) {
                    window.queue.pop();
                    window.queueLength--;

                    student.waitTime = student.serviceTime;
                    student.finishTime = student.arrivalTime + student.serviceTime;
                    totalWaitTimeSeconds += student.waitTime;

                    // 打包或堂食
                    if (student.isTakeaway) {
                        recordHistoryEvent(serviceFinishMinute * 60,
                            "student-" + to_string(student.id + 1) + " took takeaway",
                            0, 0, 1, 0, 1, 0);
                    }
                    else {
                        // 检查是否有空座位
                        int occupiedNow = 0;
                        if (minute >= 0 && minute < (int)occupiedSeatsHistory.size()) {
                            occupiedNow = occupiedSeatsHistory[minute];
                        }
                        if (occupiedNow < config.baseConfig.totalSeats) {
                            recordHistoryEvent(serviceFinishMinute * 60,
                                "student-" + to_string(student.id + 1) + " seated for dine-in",
                                0, 0, 1, 1, 0, 0);
                            student.leaveTime = student.finishTime + student.diningTime;
                        }
                        else {
                            // 无座位，强制打包
                            recordHistoryEvent(serviceFinishMinute * 60,
                                "student-" + to_string(student.id + 1) + " forced takeaway: no seat",
                                0, 0, 1, 0, 1, 0);
                            student.isTakeaway = true;
                            student.leaveTime = student.finishTime;
                        }
                    }
                }
                else {
                    break;
                }
            }
        }

        // 处理离开事件
        for (auto& student : students) {
            if (student.isAbandon) continue;
            if (student.isTakeaway) continue;
            int leaveMinute = static_cast<int>(student.leaveTime / 60);
            if (leaveMinute == minute && student.leaveTime > 0) {
                recordHistoryEvent(leaveMinute * 60,
                    "student-" + to_string(student.id + 1) + " left canteen after dining",
                    0, 0, 0, 0, 0, 1);
            }
        }

        // 更新队列峰值
        updateQueuePeaks(minute);

        // 更新座位统计
        updateSeatStats(minute);

        // 记录分钟快照
        takeMinuteSnapshot(minute);
    }
}

void DiningSimulation::finalizeTimeline() {
    // 构建时间线
    report.summary.timeline.clear();
    for (const auto& pair : timelineSnapshots) {
        report.summary.timeline.push_back(pair.second);
    }

    // 设置历史事件
    report.summary.history = historyEvents;

    // 计算平均总队列大小
    if (!totalQueueSizeHistory.empty()) {
        double sum = 0;
        for (int val : totalQueueSizeHistory) {
            sum += val;
        }
        report.summary.avgTotalQueueSize = sum / totalQueueSizeHistory.size();
    }

    // 计算平均占用座位数
    if (!occupiedSeatsHistory.empty()) {
        double sum = 0;
        for (int val : occupiedSeatsHistory) {
            sum += val;
        }
        report.summary.avgOccupiedSeats = sum / occupiedSeatsHistory.size();
    }

    // 计算座位利用率
    if (config.baseConfig.totalSeats > 0) {
        report.summary.seatUtilizationRate = report.summary.avgOccupiedSeats /
            config.baseConfig.totalSeats;
    }

    // 最终座位状态
    if (!occupiedSeatsHistory.empty()) {
        report.summary.occupiedSeats = occupiedSeatsHistory.back();
        report.summary.emptySeats = max(0, config.baseConfig.totalSeats -
            report.summary.occupiedSeats);
    }

    // 模拟结束时间
    report.summary.simulationEndTimeSeconds = static_cast<int>(config.duration * 3600);
    report.summary.simulationEndTimeMinutes = static_cast<int>(config.duration * 60);
    report.summary.totalSeats = config.baseConfig.totalSeats;
}

void DiningSimulation::calculateSummaryMetrics() {
    report.summary.arrivedCount = arrivedCount;
    report.summary.abandonedCount = abandonedCount;
    report.summary.servedCount = servedCount;
    report.summary.dineInCount = dineInCount;
    report.summary.takeawayCount = takeawayCount;
    report.summary.leaveCount = leaveCount;

    if (servedCount > 0) {
        report.summary.avgWaitTimeMinutes = totalWaitTimeSeconds / servedCount / 60.0;
        report.summary.totalWaitTimeMinutes = totalWaitTimeSeconds / 60.0;
    }

    report.summary.maxQueueSize = maxQueueSize;
    report.summary.peakTimeMinutes = peakTimeMinutes;
    report.summary.peakWindowId = peakWindowId;
    report.summary.maxTotalQueueSize = maxTotalQueueSize;
    report.summary.maxOccupiedSeats = maxOccupiedSeats;
}

void DiningSimulation::runSimulation() {
    try {
        initializeSimulation();
        generateArrivals();
        processArrivals();
        finalizeTimeline();
        calculateSummaryMetrics();

        // 填充元数据
        report.metadata.reportVersion = "1.0.0";
        report.metadata.reportId = generateReportId();
        report.metadata.effectiveSeed = config.seed;
        report.metadata.generatedAt = getCurrentTimestamp();
        report.metadata.generatedAtEpochMillis = getCurrentTimestampMillis();

        // 保存配置快照
        report.config = config;
    }
    catch (const exception& e) {
        cerr << "仿真运行出错: " << e.what() << endl;
    }
}

SimulationReport DiningSimulation::getReport() {
    return report;
}

string DiningSimulation::generateReportId() {
    auto now = chrono::system_clock::now();
    auto timestamp = chrono::duration_cast<chrono::milliseconds>(
        now.time_since_epoch()).count();

    // 生成简化ID
    stringstream ss;
    ss << hex << timestamp;
    return ss.str();
}

string DiningSimulation::getCurrentTimestamp() {
    auto now = chrono::system_clock::now();
    time_t now_time = chrono::system_clock::to_time_t(now);

#ifdef _WIN32
    struct tm time_info;
    localtime_s(&time_info, &now_time);
    char buf[64];
    strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%S", &time_info);
#else
    struct tm time_info;
    localtime_r(&now_time, &time_info);
    char buf[64];
    strftime(buf, sizeof(buf), "%Y-%m-%dT%H:%M:%S", &time_info);
#endif

    return string(buf);
}

long long DiningSimulation::getCurrentTimestampMillis() {
    auto now = chrono::system_clock::now();
    return chrono::duration_cast<chrono::milliseconds>(now.time_since_epoch()).count();
}

void DiningSimulation::printReport() {
    cout << "\n========================================" << endl;
    cout << "          仿真报告" << endl;
    cout << "========================================" << endl;

    cout << fixed << setprecision(2);
    cout << "\n报告ID: " << report.metadata.reportId << endl;
    cout << "有效种子: " << report.metadata.effectiveSeed << endl;
    cout << "生成时间: " << report.metadata.generatedAt << endl;

    cout << "\n【计数指标】" << endl;
    cout << "到达人数: " << report.summary.arrivedCount << endl;
    cout << "放弃人数: " << report.summary.abandonedCount << endl;
    cout << "服务人数: " << report.summary.servedCount << endl;
    cout << "堂食人数: " << report.summary.dineInCount << endl;
    cout << "打包人数: " << report.summary.takeawayCount << endl;
    cout << "离开人数: " << report.summary.leaveCount << endl;

    cout << "\n【等待时间指标】" << endl;
    cout << "平均等待时间: " << report.summary.avgWaitTimeMinutes << " 分钟" << endl;
    cout << "总等待时间: " << report.summary.totalWaitTimeMinutes << " 分钟" << endl;

    cout << "\n【队列峰值指标】" << endl;
    cout << "最大队列长度: " << report.summary.maxQueueSize << endl;
    cout << "峰值时间(分钟): " << report.summary.peakTimeMinutes << endl;
    cout << "峰值窗口ID: " << report.summary.peakWindowId << endl;
    cout << "最大总队列: " << report.summary.maxTotalQueueSize << endl;
    cout << "平均总队列: " << report.summary.avgTotalQueueSize << endl;

    cout << "\n【座位指标】" << endl;
    cout << "座位总数: " << report.summary.totalSeats << endl;
    cout << "占用座位: " << report.summary.occupiedSeats << endl;
    cout << "空座位: " << report.summary.emptySeats << endl;
    cout << "最大占用座位: " << report.summary.maxOccupiedSeats << endl;
    cout << "平均占用座位: " << report.summary.avgOccupiedSeats << endl;
    cout << "座位利用率: " << (report.summary.seatUtilizationRate * 100) << "%" << endl;
}

void DiningSimulation::exportToJson(const string& filename) {
    ofstream file(filename);
    if (!file.is_open()) {
        cerr << "无法打开文件: " << filename << endl;
        return;
    }

    file << "{\n";
    file << "  \"report_version\": \"" << report.metadata.reportVersion << "\",\n";
    file << "  \"report_id\": \"" << report.metadata.reportId << "\",\n";
    file << "  \"effective_seed\": " << report.metadata.effectiveSeed << ",\n";
    file << "  \"config\": {\n";
    file << "    \"simulation_name\": \"" << report.config.simulationName << "\",\n";
    file << "    \"duration\": " << report.config.duration << ",\n";
    file << "    \"arrival_rate\": " << report.config.arrivalRate << ",\n";
    file << "    \"queue_limit\": " << report.config.queueLimit << ",\n";
    file << "    \"pack_probability\": " << report.config.packProbability << ",\n";
    file << "    \"seed\": " << report.config.seed << ",\n";
    file << "    \"base_config\": {\n";
    file << "      \"window_count\": " << report.config.baseConfig.windowCount << ",\n";
    file << "      \"total_seats\": " << report.config.baseConfig.totalSeats << ",\n";
    file << "      \"total_students\": " << report.config.baseConfig.totalStudents << "\n";
    file << "    },\n";
    file << "    \"weather_config\": {\n";
    file << "      \"current_weather\": \"" << report.config.weatherConfig.currentWeather << "\",\n";
    file << "      \"weather_impact_factor\": " << report.config.weatherConfig.weatherImpactFactor << "\n";
    file << "    },\n";
    file << "    \"random_bounds\": {\n";
    file << "      \"arrival_interval\": " << report.config.randomBounds.arrivalInterval << ",\n";
    file << "      \"service_range\": [" << report.config.randomBounds.serviceRange[0]
        << ", " << report.config.randomBounds.serviceRange[1] << "],\n";
    file << "      \"dining_range\": [" << report.config.randomBounds.diningRange[0]
        << ", " << report.config.randomBounds.diningRange[1] << "],\n";
    file << "      \"preference_range\": [" << report.config.randomBounds.preferenceRange[0]
        << ", " << report.config.randomBounds.preferenceRange[1] << "]\n";
    file << "    }\n";
    file << "  },\n";
    file << "  \"summary\": {\n";
    file << "    \"arrived_count\": " << report.summary.arrivedCount << ",\n";
    file << "    \"abandoned_count\": " << report.summary.abandonedCount << ",\n";
    file << "    \"served_count\": " << report.summary.servedCount << ",\n";
    file << "    \"dine_in_count\": " << report.summary.dineInCount << ",\n";
    file << "    \"takeaway_count\": " << report.summary.takeawayCount << ",\n";
    file << "    \"leave_count\": " << report.summary.leaveCount << ",\n";
    file << "    \"avg_wait_time_minutes\": " << report.summary.avgWaitTimeMinutes << ",\n";
    file << "    \"total_wait_time_minutes\": " << report.summary.totalWaitTimeMinutes << ",\n";
    file << "    \"peak_time_minutes\": " << report.summary.peakTimeMinutes << ",\n";
    file << "    \"peak_window_id\": " << report.summary.peakWindowId << ",\n";
    file << "    \"max_queue_size\": " << report.summary.maxQueueSize << ",\n";
    file << "    \"max_total_queue_size\": " << report.summary.maxTotalQueueSize << ",\n";
    file << "    \"avg_total_queue_size\": " << report.summary.avgTotalQueueSize << ",\n";
    file << "    \"max_occupied_seats\": " << report.summary.maxOccupiedSeats << ",\n";
    file << "    \"avg_occupied_seats\": " << report.summary.avgOccupiedSeats << ",\n";
    file << "    \"seat_utilization_rate\": " << report.summary.seatUtilizationRate << ",\n";
    file << "    \"simulation_end_time_seconds\": " << report.summary.simulationEndTimeSeconds << ",\n";
    file << "    \"simulation_end_time_minutes\": " << report.summary.simulationEndTimeMinutes << ",\n";
    file << "    \"total_seats\": " << report.summary.totalSeats << ",\n";
    file << "    \"occupied_seats\": " << report.summary.occupiedSeats << ",\n";
    file << "    \"empty_seats\": " << report.summary.emptySeats << "\n";
    file << "  },\n";
    file << "  \"generated_at\": \"" << report.metadata.generatedAt << "\",\n";
    file << "  \"generated_at_epoch_millis\": " << report.metadata.generatedAtEpochMillis << "\n";
    file << "}\n";

    file.close();
    cout << "报告已导出到: " << filename << endl;
}