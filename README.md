# 大学食堂高峰客流仿真系统

本项目是一个 Spring Boot + React 的离散事件仿真系统，用于模拟大学食堂在平峰、高峰、座位紧张、打包窗口干预和雨天应急场景下的到达、排队、服务、找座、就餐和离开过程。

## 核心能力

- 总量守恒的到达模型：到达率作为权威人数来源，峰值曲线只改变时间分布。
- 成套模型样例：后端提供 5 套可直接运行的业务场景。
- 等待体验模型：提供典型等待、P50、P75、P90、长等待率和边界样本占比。
- 座位占用追踪：使用座位秒积分计算利用率，并提供座位状态图。
- 打包决策解释：记录基础概率、偏好、座位压力、等待压力、队列压力和天气因子。
- 轻量报告接口：默认不返回完整 history，避免 10^3 量级数据造成 JSON 膨胀。
- 高级统计后处理：通过 C++ 子系统提供 Bootstrap 95% 置信区间、Gini 瓶颈打分、跨场景 ANOVA。

## 快速启动

一键构建 + 启动(集成前端):

```powershell
mvn spring-boot:run
```

`mvn` 会经由 `frontend-maven-plugin` 自动下载 Node 20、跑 `npm install` + `npm run build:backend`,把前端产物写到 `src/main/resources/static/frontend/`,然后启动后端。打开 `http://localhost:8080/frontend/` 即可看到完整 UI。

跳过前端构建(只跑后端,适合 CI / 离线环境):

```powershell
mvn spring-boot:run -DskipFrontend=true
```

前端开发热更新(改 React 源码即时生效,需另起后端):

```powershell
cd sun
npm install
npm run dev          # 5173 端口,/api 自动代理到 8080
```

如果只构建生产前端,不打 jar:

```powershell
cd sun
npm install
npm run build:backend     # 写入 ../src/main/resources/static/frontend/
```

C++ 分析子系统(可选,Java fallback 已内置):

```powershell
cd dataAnalyze
msbuild Project3.sln /p:Configuration=Release
```

构建产物 `canteen-analyze.exe` 应放置于 `dataAnalyze/bin/`,Spring Boot 通过 `ProcessBuilder` 调用。**未编译 exe 时**,后端会用 Java 内置的 `InternalStatisticsAnalyzer` 计算等价指标(置信区间、Gini 瓶颈、Monte Carlo、ANOVA),前端高级统计模块照常工作,响应里会有 `computed_by: "java-internal"` 标记。

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
| `lunch_peak_pressure` | 午高峰压力测试 | 600 人级高峰验证 |
| `seat_shortage` | 座位紧张模型 | 验证占座与打包触发 |
| `takeaway_intervention` | 打包窗口干预 | 验证增设打包窗口效果 |
| `rain_emergency` | 雨天应急预案 | 验证天气与压力联动 |
| `group_high_concentration` | 群体高密度到达 | 验证拼桌、成组占座、打包率联动 |

示例请求文件见 [examples/scenarios/canteen-scenario-set.json](examples/scenarios/canteen-scenario-set.json)。

## 常见用例

- 验证午高峰：运行 `lunch_peak_pressure`，检查到达人数是否为 600。
- 比较干预效果：同时运行 `lunch_peak_pressure` 和 `takeaway_intervention`，比较典型等待、打包率和座位利用率。
- 检查座位压力：运行 `seat_shortage`，查看等待体验和座位状态图。
- 验证雨天预案：运行 `rain_emergency`，观察天气因子对打包率和排队压力的影响。
- 验证成组占座：运行 `group_high_concentration`，检查座位热力图中橙色「成组占用」格子是否每帧均在显示，并核对打包率联动。

## 分析子系统

后端通过 `AnalysisController → ExternalAnalysisService → C++ binary` 三级链路把 Java 报告交给 C++ 做高级统计后处理：

```http
POST /api/analysis/run
Content-Type: application/json

{ "report_id": "<simulation report id>" }
```

```http
POST /api/analysis/cross-scenario
Content-Type: application/json

{ "scenario_ids": ["lunch_peak_pressure", "takeaway_intervention"] }
```

返回 `data` 包含 `confidence_intervals.{wait,utilization,takeaway_rate}` / `bottleneck_score` / `anova` 三类字段。
报告不存在 → 返回 503 + `available: false`；C++ binary 缺失但报告存在 → 返回 200 + Java fallback 实现的统计结果（由 `InternalStatisticsAnalyzer` 提供，标记 `source: "java_fallback"`）。前端 `<AdvancedStatsPanel>` 在两种情况下都能正常渲染。

设计依据见 `docs/analysis/adr/002-cpp-as-postprocessor.md`。

## 目录与归档说明

```
src_24281231/
├── src/                     Java 后端
├── sun/                     React + Tailwind + ECharts 前端
├── dataAnalyze/             C++ 统计后处理 CLI
├── reports/                 仿真报告（.gitignore，仅留 .gitkeep）
└── examples/                请求示例

docs/
├── pdf_text/                立项书与开发阶段报告（PDF→Markdown）
├── analysis/                中期审查报告 / ADR / 选型说明
├── legacy/                  归档区：dataPre/canteen-viz 与早期 run-logs
└── scripts/                 辅助脚本（PDF 转换等）
```

`docs/legacy/` 内容**不参与运行**，仅作历史参考。详见 `docs/legacy/README.md` 与 `docs/analysis/adr/004-legacy-archival.md`。

## 验证命令

```powershell
mvn test
cd sun
npm install
npm run build
```
