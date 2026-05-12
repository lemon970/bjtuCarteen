#ifndef DINING_SIMULATION_H
#define DINING_SIMULATION_H

#include <vector>
#include <string>
#include <queue>
#include <map>
#include <ctime>
#include <random>

// ==================== 配置结构体（对应API请求） ====================

struct BaseConfig {
    int windowCount = 5;
    int totalSeats = 50;
    int totalStudents = 0;
};

struct WeatherConfig {
    std::string currentWeather = "unknown";
    double weatherImpactFactor = 1.0;
};

struct RandomBounds {
    int arrivalInterval = 0;           // 秒，<=0时由arrivalRate推导
    std::vector<int> serviceRange = { 60, 300 };    // [min, max] 秒
    std::vector<int> diningRange = { 600, 1800 };   // [min, max] 秒
    std::vector<double> preferenceRange = { 0.1, 0.3 };
};

struct SimulationConfig {
    std::string simulationName = "default-simulation";
    double duration = 1.0;             // 小时
    double arrivalRate = 60.0;         // 人/小时
    int queueLimit = 10;               // 窗口排队上限
    double packProbability = 0.2;      // 打包概率
    long long seed = 0;                // 随机种子，0表示使用时间种子

    BaseConfig baseConfig;
    WeatherConfig weatherConfig;
    RandomBounds randomBounds;

    // 规范化后的值（运行时计算）
    int effectiveArrivalInterval = 0;   // 秒
    int effectiveTotalStudents = 0;
    int effectiveServiceMin = 60;
    int effectiveServiceMax = 300;
    int effectiveDiningMin = 600;
    int effectiveDiningMax = 1800;
};

// ==================== 学生结构体 ====================

struct Student {
    int id;
    double arrivalTime;     // 到达时间（秒）
    double serviceTime;     // 服务时间（秒）
    double diningTime;      // 就餐时间（秒）
    bool isTakeaway;        // 是否打包
    int targetWindow;
    bool isAbandon;
    double waitTime;        // 实际等待时间
    double finishTime;      // 服务完成时间
    double leaveTime;       // 离开时间
};

// ==================== 窗口结构体 ====================

struct Window {
    int windowId;
    std::queue<int> queue;  // 存储学生ID
    int queueLength;

    Window(int id) : windowId(id), queueLength(0) {}
};

// ==================== 分钟级快照（对应METRICS.md timeline） ====================

struct MinuteSnapshot {
    int minute;                             // 分钟索引
    std::vector<int> windowQueueSizes;      // 各窗口排队长度
    int totalQueueSize;                     // 总排队长度
    int occupiedSeats;                      // 占用座位数
    int emptySeats;                         // 空座位数
    int cumulativeArrivedCount;             // 累计到达人数
    int cumulativeAbandonedCount;           // 累计放弃人数
    int cumulativeServedCount;              // 累计服务人数
    int cumulativeDineInCount;              // 累计堂食人数
    int cumulativeTakeawayCount;            // 累计打包人数
    int cumulativeLeaveCount;               // 累计离开人数
};

// ==================== 事件历史（对应API summary.history） ====================

struct HistoryEvent {
    int time;                       // 事件发生时间（秒）
    std::vector<int> queueSizes;    // 各窗口排队长度
    int totalQueueSize;             // 总排队长度
    int occupiedSeats;              // 占用座位数
    int emptySeats;                 // 空座位数
    std::string eventMessage;       // 事件描述
    int arrivedCount;               // 累计到达
    int abandonedCount;             // 累计放弃
    int servedCount;                // 累计服务
    int dineInCount;                // 累计堂食
    int takeawayCount;              // 累计打包
    int leaveCount;                 // 累计离开
};

// ==================== 摘要统计（对应METRICS.md） ====================

struct SummaryMetrics {
    // 计数指标
    int arrivedCount = 0;
    int abandonedCount = 0;
    int servedCount = 0;
    int dineInCount = 0;
    int takeawayCount = 0;
    int leaveCount = 0;

    // 等待时间指标
    double avgWaitTimeMinutes = 0.0;
    double totalWaitTimeMinutes = 0.0;

    // 队列峰值指标
    int peakTimeMinutes = 0;
    int peakWindowId = -1;
    int maxQueueSize = 0;
    int maxTotalQueueSize = 0;
    double avgTotalQueueSize = 0.0;

    // 座位指标
    int maxOccupiedSeats = 0;
    double avgOccupiedSeats = 0.0;
    double seatUtilizationRate = 0.0;
    int totalSeats = 0;
    int occupiedSeats = 0;
    int emptySeats = 0;

    // 时间线
    int simulationEndTimeSeconds = 0;
    int simulationEndTimeMinutes = 0;

    // 历史事件和时间线
    std::vector<HistoryEvent> history;
    std::vector<MinuteSnapshot> timeline;
};

// ==================== 报告元数据（对应API响应） ====================

struct ReportMetadata {
    std::string reportVersion = "1.0.0";
    std::string reportId;
    long long effectiveSeed = 0;
    std::string generatedAt;
    long long generatedAtEpochMillis = 0;
};

// ==================== 完整报告（对应API响应data） ====================

struct SimulationReport {
    ReportMetadata metadata;
    SimulationConfig config;
    SummaryMetrics summary;
};

// ==================== 就餐仿真类 ====================

class DiningSimulation {
private:
    SimulationConfig config;
    SimulationReport report;
    std::vector<Window> windows;
    std::vector<Student> students;

    // 随机数生成器
    std::mt19937 rng;

    // 统计变量
    int arrivedCount;
    int abandonedCount;
    int servedCount;
    int dineInCount;
    int takeawayCount;
    int leaveCount;
    double totalWaitTimeSeconds;

    // 队列峰值追踪
    int maxQueueSize;
    int peakTimeMinutes;
    int peakWindowId;
    int maxTotalQueueSize;
    std::vector<int> totalQueueSizeHistory;

    // 座位追踪
    std::vector<int> occupiedSeatsHistory;
    int maxOccupiedSeats;

    // 时间线快照
    std::map<int, MinuteSnapshot> timelineSnapshots;
    std::vector<HistoryEvent> historyEvents;

    // 辅助函数
    void normalizeConfig();
    void initializeSimulation();
    void generateArrivals();
    void processArrivals();
    void updateQueuePeaks(int minute);
    void updateSeatStats(int minute);
    void recordHistoryEvent(int time, const std::string& message,
        int arrivedDelta = 0, int abandonedDelta = 0,
        int servedDelta = 0, int dineInDelta = 0,
        int takeawayDelta = 0, int leaveDelta = 0);
    void takeMinuteSnapshot(int minute);
    void finalizeTimeline();
    void calculateSummaryMetrics();
    std::string generateReportId();
    std::string getCurrentTimestamp();
    long long getCurrentTimestampMillis();

public:
    DiningSimulation();
    void configure(const SimulationConfig& cfg);
    void runSimulation();
    SimulationReport getReport();
    void printReport();
    void exportToJson(const std::string& filename);
};

#endif