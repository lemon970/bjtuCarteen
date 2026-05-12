# 前端中文多界面重构与接口对齐说明

## 重构目标

将 `sun` 中原有单页英文仿真界面重构为三套中文业务界面，并保持 Spring Boot 后端核心仿真逻辑不变。前端不再展示原始 JSON 文件内容，只保留配置导入、结果可视化、分页历史和分析结论。

## 风格统一参考清单

- 技术栈：沿用 `sun` 的 React 18 + Vite，避免引入额外路由和状态库。
- 组件组织：按 `api / components / pages / utils` 分层，页面组件使用 PascalCase。
- 样式方案：集中维护 `sun/src/index.css`，保留同学代码中偏轻量、表单驱动、卡片式数据展示的风格。
- 交互模式：统一使用顶部导航切换“信息输入 / 数据展示 / 数据分析”，用按钮触发仿真、加载最近报告、分页历史。
- 数据规模策略：前端只展示摘要、时间线和分页历史，不展示完整 JSON，适配 10^3 量级运行。

## 前端文件结构

```text
sun/
  index.html
  src/
    App.jsx
    constants.js
    index.css
    main.jsx
    api/
      simulationApi.js
    components/
      AppLayout.jsx
      HistoryTable.jsx
      MetricCard.jsx
      ReplayPanel.jsx
      TimelineChart.jsx
    pages/
      AnalysisPage.jsx
      DisplayPage.jsx
      InputPage.jsx
    utils/
      simulation.js
```

## 三个界面

- 信息输入界面：集中管理仿真名称、学生数量、到达率、窗口、座位、打包率、天气、随机服务范围和高峰配置，支持导入配置文件但不展示 JSON 原文。
- 数据展示界面：展示关键指标、趋势图、座位与排队快照、分页历史表格。
- 数据分析界面：展示吞吐、等待、座位利用率、拥堵峰值、参数复盘和中文结论。

## 接口对齐

- 统一入口：`sun/src/api/simulationApi.js`
- 运行仿真：`POST /api/simulation/run`
- 最近报告：`GET /api/simulation/report/latest`
- 分页历史：`GET /api/simulation/report/{reportId}/history?page=1&page_size=500`
- 请求字段：前端统一输出后端可识别的 `snake_case` 配置。
- 响应字段：前端从 `{ code, message, data }` 中读取 `data.report_id / data.config / data.summary`。

## 后端适配

- `src/main/java/com/bjtu/simulation/service/SimulationReportRepository.java`
  - 报告写入改为临时文件替换并增加短暂重试，降低 Windows 文件占用导致的 500 风险。
- `src/test/java/com/bjtu/simulation/controller/SimulationApiIntegrationTest.java`
  - 补充静态前端入口测试，验证 `/`、`/index.html`、`/frontend/`、`/frontend/index.html` 可访问。

## 清理与保留

- 保留后端核心仿真、报告、分页历史逻辑。
- 保留 `sun` 作为正式前端工程。
- 参考 `dataPre` 的可视化思路，但不直接混入 CDN 和原生 DOM 架构。
- `dataIn` 当前主要是日志与进程文件，不纳入正式前端运行链路。
- 移除页面层面的原始 JSON 展示，避免大数据量下渲染和传输压力。

## 验证结果

```text
npm run build
构建通过，产物输出到 src/main/resources/static/frontend/

mvn test
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 运行方式

```text
后端运行后访问：
http://localhost:8080/frontend/
```

