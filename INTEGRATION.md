# 项目整合说明

## 当前整合策略

- **Java/Spring Boot 后端**仍是统一仿真服务入口，接口前缀为 `/api/simulation` 与 `/api/analysis`。
- **`sun/`** 是当前统一前端工程（React + Vite + Tailwind v3 + ECharts 5.5），生产构建产物可同步到后端 `static/frontend`。
- **`dataAnalyze/`** 已重构为 C++ 统计后处理 CLI，通过 `ProcessBuilder` 由 `ExternalAnalysisService` 调用，详见 `docs/analysis/adr/002-cpp-as-postprocessor.md`。
- **`docs/legacy/canteen-viz-prototype/`**（原 `dataPre/canteen-viz`）和 **`docs/legacy/run-logs/`**（原 `dataIn`）已归档，仅作历史参考，不参与运行。

## 子系统接入点

| 子系统 | 入口 | 接入位置 | 文档 |
|---|---|---|---|
| Java 仿真引擎 | `/api/simulation/**` | 4 个 Controller | `API.md §1-4` |
| C++ 统计后处理 | `/api/analysis/run`、`/api/analysis/cross-scenario` | `AnalysisController` → `ExternalAnalysisService` → `dataAnalyze/bin/canteen-analyze.exe` | `API.md §7` / ADR 002 |
| React 前端 | 浏览器 → `/frontend/` | `sun/src/App.jsx` 三 Tab | `FRONTEND_REFACTOR_REPORT.md` |

## 前端能力

`sun` 前端已接入后端接口：

- `POST /api/simulation/run`：提交仿真参数并生成报告。
- `GET /api/simulation/report/latest`：读取最新报告。
- `GET /api/simulation/report/{id}/history?page=1&page_size=20`：分页读取事件快照，避免大 JSON 一次性返回。
- `GET /api/simulation/scenarios` / `POST /api/simulation/scenarios/run`：场景目录与批量运行。
- `POST /api/analysis/run` / `POST /api/analysis/cross-scenario`：调用 C++ 后处理（不可用时优雅降级）。

前端整合后的功能：

- 参数传入和 JSON 配置导入。
- 摘要 KPI 查看（Tailwind 大数字卡）。
- timeline 趋势快照（ECharts 双 y 轴）。
- 时间轴回放（播放 / 暂停 / 重置 / 速度调节）。
- 窗口排队条形展示（普通 vs 打包窗口着色区分）。
- 场景对比 Tab（多场景批量结果横向比较）。
- 高级统计面板（C++ 后处理结果 / CI / 瓶颈分 / ANOVA）。
- 历史事件分页查看。

## 运行方式

开发模式：

```bash
cd sun
npm install
npm run dev
```

同时启动后端：

```bash
mvn spring-boot:run
```

前端开发服务通过 Vite 代理访问后端 `/api`。

C++ 分析子系统单独构建（仅 Windows + VS 工具链）：

```powershell
cd dataAnalyze
msbuild Project3.sln /p:Configuration=Release
# 产物：dataAnalyze/bin/canteen-analyze.exe
```

生产/整合模式：

```bash
cd sun
npm install
npm run build
cd ..
mvn spring-boot:run
```

浏览器访问：

```text
http://localhost:8080/frontend/
```

## 已验证

- `mvn test`：50 项全绿（含 `ExternalAnalysisServiceTest` 5 项 + `AnalysisControllerIntegrationTest` 3 项）。
- `npm run build`：产物 `dist/assets/index-*.js` 大小 726.80 KB（gzip 240.22 KB），CSS 30.69 KB（gzip 5.76 KB）。
- C++ binary 缺失时 `/api/analysis/run` 返回 `code: 503` + `available: false`，主系统照常运行。
