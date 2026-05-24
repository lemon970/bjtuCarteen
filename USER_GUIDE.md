# 用户操作指南

## 信息输入页

信息输入页的主入口是“成套模型样例”。建议先加载一个模型，再微调参数。

- 单场景加载：点击某个模型卡片的“加载此模型”。
- 成组运行：勾选多个模型后点击“成组运行”。
- 当前配置运行：修改参数后点击“运行当前配置”。
- 高级参数：默认折叠，包含峰值窗口、分布上下限、成组到达和步行参数。

## 运行模式（auto / sync / async）

页面右上"运行模式"下拉控制单次仿真走同步还是异步路径：

- **自动**（默认）：根据参数自动判定。当估算到达人数 ≥ 8000，或仿真时长 ≥ 4 小时时，自动切到异步避免 HTTP 长等待；其余情况走同步。估算公式 `min(duration × arrivalRate, totalStudents)`，`学生上限 = 0` 视为无上限。
- **同步**：强制走 `POST /api/simulation/run`，HTTP 阻塞直到仿真完成。适合短仿真、调试或异步路径出问题时回退。
- **异步**：强制走 `POST /api/simulation/run/async`。提交后立即返回 task_id，前端按 1s → 2s → 5s 节奏轮询 `/task/{id}/status`，`status=COMPLETED` 后再请求 `/api/simulation/report/{id}` 拉完整报告再跳转数据展示页。

异步模式下用户可见的提示：

- 提交成功：消息条显示"已提交仿真任务，正在等待后端执行…"。
- 完成：消息条显示"仿真完成，报告编号：{id}"，自动跳转数据展示页。
- 失败：消息条显示"仿真失败：{error_message}"。
- 等待超时（10 分钟硬上限）：消息条显示"仿真等待超时（10 分钟），后端任务可能仍在执行。task_id：…"，前端停止刷新；后端任务表 30 分钟内仍可凭 task_id 重新查询。
- 连续轮询失败：3 次连续状态请求失败后停止刷新。

异步模式不提供"取消任务"按钮：后端当前没有 cancel 路径，关闭页面或重新提交只会停止前端轮询，服务端 worker 仍会把任务跑完。

五个内置模型：

- 平峰基线：用于正常负载对照。
- 午高峰压力测试：用于 300 人/小时、2 小时、600 人级验证。
- 座位紧张模型：用于验证座位不足时的找座和打包触发。
- 打包窗口干预：用于比较增加打包窗口后的变化。
- 雨天应急预案：用于观察雨天偏好和排队压力。

## 数据展示页

展示页按三层组织：

1. 关键 KPI：到达人数、完成服务、典型等待、座位利用率、打包比例、峰值排队。
2. 详细分解：等待体验、队列趋势、座位占用率、座位状态图。
3. 事件快照：按分页读取 history，避免大 JSON 卡顿。

重点指标解释：

- 典型等待：稳态样本 10% 截尾均值，优先代表多数学生体验。
- 全量均值：兼容旧接口，容易受开头和结尾阶段影响。
- P90：长等待风险指标。
- 边界样本占比：衡量开头和结尾样本对全量均值的影响。
- 座位利用率：占用座位秒 / 总座位秒。

## 模型分析页

分析页用于解释结果而不是罗列原始数据。

- 结论摘要：自动生成等待、座位和队列结论。
- 等待体验模型：展示典型等待、百分位、长等待率和等待分布。
- 瓶颈诊断：识别本次仿真中座位、窗口或队列哪一环最先吃紧。结论用白话描述（如"座位偏紧"、"窗口服务繁忙"、"打包窗口繁忙"、"到达冲击明显"），并给出一句操作建议；4 类资源利用率均低于 0.85 阈值时显示"系统均衡"。具体诊断证据（指标名/实测值/阈值/窗口 ID）在结论下方折叠区按需展开，`window_id` 为 0-based 内部索引（不擅自 +1）。
- 打包决策解释：展示最终概率和各压力因子的贡献。
- 参数复盘：核对归一化后的核心配置。

## 数据导出

数据展示页在存在报告编号时可导出 CSV。CSV 包含：

- 到达样本
- 打包决策样本
- 分页历史快照

## 队列选择模型（queue_choice_model，RFC-009）

`base_config.queue_choice_model` 控制学生在到达时的窗口偏好生成方式，默认值 `STATIC_SPLIT` 与历史行为完全一致：

- **STATIC_SPLIT（默认）**：每个学生的 `windowPreference` 在所有窗口上均匀抽样；后续 score-based 选择策略不变；报告 schema 与旧版字节级一致，**不输出** `window_choice_metrics` 顶级字段。
- **PREFERENCE_AWARE**：在 `STATIC_SPLIT` 基础上，按 `base_config.window_attractiveness` 配置把普通窗口分为 popular / normal / cold 三类，按吸引力做 cumulative-weight 加权抽样生成 `windowPreference`。打包窗口仍参与抽样池，权重等于 `normal_attractiveness`（中性）。WindowSelectionPolicy、legacy score、StudentProfile 字段均不变。

`window_attractiveness` 关键字段（缺省时按默认填充并写 `window_attractiveness_missing_filled_default` warning）：

- `popular_window_ratio` / `cold_window_ratio`：占普通窗口的比例，二者之和必须 ≤ 1.0。
- `popular_attractiveness ≥ normal_attractiveness ≥ cold_attractiveness > 0`：违反则配置校验直接 fail-fast。

PREFERENCE_AWARE 报告会在 `summary.window_choice_metrics` 输出诊断指标：popular / normal / cold 三档窗口数、preference 占比、served 占比、平均等待分钟、`max_window_queue_gap`、`window_served_count_cv`。所有 share 类指标的分母锁定**普通窗口集合**（POPULAR + NORMAL + COLD），打包窗口不计入分母。

