# 项目整合说明

## 当前整合策略

- Java/Spring Boot 后端仍是统一仿真服务入口，接口前缀为 `/api/simulation`。
- `sun` 是当前统一前端工程，使用 React + Vite。
- `dataPre/canteen-viz` 是前端展示原型，已提取其“时间轴回放、窗口队列展示、统计报告”思路并合入 `sun`，原目录保留不改。
- `dataAnalyze` 是独立 C++ 分析/仿真代码，当前保留原始成员交付，不直接替换 Java 后端。
- `dataIn` 当前主要是历史运行日志和 PID 文件，不作为源码模块接入。

## 前端能力

`sun` 前端已接入后端现有接口：

- `POST /api/simulation/run`：提交仿真参数并生成报告。
- `GET /api/simulation/report/latest`：读取最新报告。
- `GET /api/simulation/report/{id}/history?page=1&page_size=20`：分页读取事件快照，避免大 JSON 一次性返回。

前端保留并整合的功能：

- 参数传入和 JSON 配置导入。
- 摘要 KPI 查看。
- timeline 趋势快照。
- 时间轴回放。
- 窗口排队条形展示。
- 统计报告区。
- 历史事件分页查看。

## 运行方式

开发模式：

```bash
cd sun
npm run dev
```

同时启动后端：

```bash
mvn spring-boot:run
```

前端开发服务通过 Vite 代理访问后端 `/api`。

生产/整合模式：

```bash
cd sun
npm run build
cd ..
mvn spring-boot:run
```

浏览器访问：

```text
http://localhost:8080/frontend/
```

## 已验证

- `npm run build` 通过，前端产物已写入 `src/main/resources/static/frontend`。
- `mvn test` 通过：27 个测试，0 失败，0 错误。
- 后端仍保留 1000 学生量级轻量报告测试，默认响应不会携带完整 history，history 改为分页读取。
