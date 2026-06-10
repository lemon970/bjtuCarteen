# 大学食堂高峰客流仿真系统

本项目是一个SpringBoot+React的离散事件仿真系统，用于模拟大学食堂在平峰、高峰、座位紧张、打包窗口干预和雨天应急场景下的到达、排队、服务、找座、就餐和离开过程。

## 核心能力

- 总量守恒的到达模型：到达率作为权威人数来源，峰值曲线只改变时间分布。
- 成套模型样例：后端提供6套可直接运行的业务场景。
- 等待体验模型：提供典型等待、P50、P75、P90、长等待率和边界样本占比。
- 座位占用追踪：使用座位秒积分计算利用率，并提供座位状态图。
- 打包决策解释：记录基础概率、偏好、座位压力、等待压力、队列压力和天气因子。
- 轻量报告接口：默认不返回完整history，避免10^3量级数据造成JSON膨胀。
- 高级统计后处理:由`InternalStatisticsAnalyzer`(纯Java)输出Bootstrap95%置信区间和Gini瓶颈打分，通过`POST /api/analysis/run`暴露给前端`<AdvancedStatsPanel>`。
- 同步/异步双路径：默认按估算到达人数与时长自动选择；长仿真走`/run/async`+polling，避免HTTP长等待。详见`USER_GUIDE.md`的“运行模式”小节。

## 快速启动

**面向终端用户(教师/演示)**:解压完整源码包后，直接双击`run.bat`。完整源码包内置JDK压缩包，不需要预装Java和Maven。详见`README-启动说明.md`。

**从GitHub克隆源码时**:仓库不提交JDK压缩包。需要本机安装JDK17+，或手动把`temurin-jdk17-windows-x64.zip`放入`java\`目录。Maven不需要安装，项目已包含MavenWrapper。

**需要重新构建时**:

```powershell
.\build.bat
```

**面向开发者**,一键构建+启动(集成前端):

```powershell
.\mvnw.cmd spring-boot:run
```

`mvnw.cmd`会经由`frontend-maven-plugin`自动下载Node20、跑`npm install`+`npm run build:backend`，把前端产物写到`src/main/resources/static/frontend/`，然后启动后端。打开`http://localhost:8080/frontend/`即可看到完整UI。

跳过前端构建(只跑后端，适合CI/离线环境):

```powershell
.\mvnw.cmd spring-boot:run -DskipFrontend=true
```

前端开发热更新(改React源码即时生效，需另起后端):

```powershell
cd sun
npm install
npm run dev          # 5173端口，/api自动代理到8080
```

如果只构建生产前端，不打jar:

```powershell
cd sun
npm install
npm run build:backend     # 写入../src/main/resources/static/frontend/
```

高级统计后处理由`InternalStatisticsAnalyzer`(纯Java)生成，无需额外构建步骤。响应中带`computed_by: "java-internal"`标记。

## 场景模型接口

获取预设模型：

```http
GET /api/simulation/scenarios
```

批量运行模型：

```http
POST /api/simulation/scenarios/run
Content-Type: application/json

{
  "scenario_ids": [
    "baseline_offpeak",
    "lunch_peak_pressure",
    "seat_shortage"
  ]
}
```

内置模型：

| ID | 名称 | 用途 |
|---|---|---|
| `baseline_offpeak` | 平峰基线 | 正常负载对照 |
| `lunch_peak_pressure` | 午高峰压力测试 | 600人级高峰验证 |
| `seat_shortage` | 座位紧张模型 | 验证占座与打包触发 |
| `takeaway_intervention` | 打包窗口干预 | 验证增设打包窗口效果 |
| `rain_emergency` | 雨天应急预案 | 验证天气与压力联动 |
| `group_high_concentration` | 群体高密度到达 | 验证拼桌、成组占座、打包率联动 |

示例请求文件见 [examples/scenarios/canteen-scenario-set.json](examples/scenarios/canteen-scenario-set.json)。

## 常见用例

- 验证午高峰：运行 `lunch_peak_pressure`，检查到达人数是否为600。
- 比较干预效果：同时运行 `lunch_peak_pressure` 和 `takeaway_intervention`，比较典型等待、打包率和座位利用率。
- 检查座位压力：运行 `seat_shortage`，查看等待体验和座位状态图。
- 验证雨天预案：运行 `rain_emergency`，观察天气因子对打包率和排队压力的影响。
- 验证成组占座：运行 `group_high_concentration`，检查座位热力图中橙色「成组占用」格子是否每帧均在显示，并核对打包率联动。

## 分析子系统

后端通过 `AnalysisController → ExternalAnalysisService → InternalStatisticsAnalyzer` 三级链路把Java报告交给纯Java后处理:

```http
POST /api/analysis/run
Content-Type: application/json

{ "report_id": "<simulation report id>" }
```

返回 `data` 包含 `confidence_intervals.{wait_time_minutes,seat_utilization_rate}` / `bottleneck.{score,gini_coefficient,worst_window_id,sustained_peak_minutes}` / `headline_metrics` 三类字段。
报告不存在→返回503+`available: false`；否则返回200+`InternalStatisticsAnalyzer`计算结果(标记`computed_by: "java-internal"`)。

高级统计架构的历史决策记录见`docs/analysis/adr/002-cpp-as-postprocessor.md`(收尾阶段已撤回C++路径，改为纯Java实现)。

## 目录与归档说明

```
src_24281231/
├── src/                     Java后端
├── sun/                     React+Tailwind+ECharts前端
├── .mvn/                    MavenWrapper配置
├── java/                    可选内置JDK压缩包与本地解压目录
├── reports/                 仿真报告（.gitignore，仅留 .gitkeep）
└── examples/                请求示例

docs/
├── pdf_text/                立项书与开发阶段报告（PDF→Markdown）
├── analysis/                中期审查报告 / ADR / 选型说明
├── legacy/                  归档区：dataPre/canteen-viz与早期run-logs
└── scripts/                 辅助脚本（PDF转换等）
```

`docs/legacy/` 内容**不参与运行**，仅作历史参考。详见 `docs/legacy/README.md` 与 `docs/analysis/adr/004-legacy-archival.md`。

## 验证命令

```powershell
.\mvnw.cmd test
cd sun
npm install
npm run build
```
