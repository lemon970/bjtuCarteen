# RFC-011/012 前端展示集成(Minimal,Spec v3)

**日期**:2026-05-24
**作者**:lemon + Claude
**状态**:Draft v3,收缩为 Minimal Integration
**取代**:本文件 v1/v2(全量方案因课程大作业范围考虑被裁剪)

---

## 0. 背景与范围调整

作为课程大作业,原 v2 全量方案(后端 4 endpoint + 前端 BatchScanPage + AnalysisTaskService + 落盘 + 顶部导航)会把项目从"食堂仿真与可视化系统"推向"仿真实验平台",偏离原项目书主题。

本轮收缩为 **Minimal Integration**:仅前端展示 RFC-011 / RFC-012 已注入 `SimulationSummary` 的字段,**不**做 RFC-010 productization。

## 1. 范围

### 1.1 做(In Scope)

1. `AnalysisPage` 展示 RFC-011 / RFC-012 已存在于 `summary` 的字段
2. 新增 3 个 panel:`BottleneckDiagnosisPanel` / `WaitExperienceProxyPanel` / `FairnessPanel`
3. `USER_GUIDE.md` 追加一段:启发式 / 同模型内相对比较

### 1.2 不做(Out of Scope)

| # | 不做项 | 理由 |
|---|---|---|
| 1 | 不新增后端 endpoint | 范围控制 |
| 2 | 不新增 `AnalysisTaskService` | 范围控制 |
| 3 | 不新增 `BatchRunController` / `SensitivityController` | 范围控制 |
| 4 | 不新增 `BatchScanPage` / 顶部导航入口 | 范围控制 |
| 5 | 不新增 `reports/batch/` / `reports/sensitivity/` 目录 | 范围控制 |
| 6 | 不接 RFC-010 batch / sensitivity 前端页面 | 范围控制 |
| 7 | 不动 RFC-010A/B/C / RFC-011 / RFC-012 service / calculator / DTO | 算法层 0 改 |
| 8 | 不引入新依赖(`pom.xml` / `package.json`) | 零依赖 |
| 9 | **不修改任何后端文件**(`src/main/java`、`src/test/java`、`pom.xml` 0 改动) | 纯前端 PR |
| 10 | 不实现 SSE / 任务轮询 / 文件落盘 / 雷达图 | 不在范围内 |

### 1.3 RFC-010 后端能力定位

RFC-010A/B/C 已实现的 `BatchRunService` / `SensitivityAnalysisService` / 11 个相关 DTO 保留为**后端实验能力**(可经单元测试与脚本调用)与 **future work**,不做产品化前端展示。后续若需 productization,独立 RFC + 独立 spec。

---

## 2. 详细设计

### 2.1 共享 helper

直接复用 `sun/src/utils/simulation.js:3` 已有 `read(obj, ...keys)`(`!== undefined && !== null` 判断,**0 视为有效值**)。3 个 panel 一律 `read(summary, snake, camel)` 兼容 snake/camel,**不**新建 `readMetric`。

### 2.2 `WaitExperienceProxyPanel`

文件:`sun/src/components/WaitExperienceProxyPanel.jsx`,入参 `{ summary }`。

字段绑定:

| UI 标签 | 字段(snake) | 单位 |
|---|---|---|
| 综合代理指数 | `wait_experience_proxy_index` | 0~1 |
| P/I 比 | `pre_process_wait_share` | 0~1 |
| 不确定性 | `wait_uncertainty_score` | 0~1 |
| 焦虑压力 | `anxiety_pressure_index` | 0~1 |
| 独食调整等待 | `solo_adjusted_wait_minutes` | 分钟 |
| 样本数 | `sample_count` | 整数(party-weighted) |

空态:`summary.wait_experience_proxy_metrics == null`(后端 < 50 样本时整对象为 null)→ 整 panel 不渲染(与 RFC-012 null 防御一致)。**不**显示 0 值。

固定文案:
> 等权融合的启发式代理指标,仅用于同模型内相对比较,不解释为真实感知等待时间。

### 2.3 `FairnessPanel`

文件:`sun/src/components/FairnessPanel.jsx`,入参 `{ summary }`。

字段绑定:

| UI 标签 | 字段 | 单位 | 阈值上色 |
|---|---|---|---|
| 等待 GINI | `wait_gini` | 0~1 | `<0.20` 绿 / `0.20~0.40` 黄 / `≥0.40` 红 |
| 非打包窗口负载 CV | `non_takeaway_window_load_cv` | 比例 | `<0.20` 绿 / `0.20~0.30` 黄 / `≥0.30` 红 |
| 跨角色差异 | `cross_role_fairness` | 分钟 | `<3` 绿 / `3~6` 黄 / `≥6` 红 |
| 样本数 | `sample_count` | 整数 | 不上色 |

空态:`summary.fairness_metrics == null` → 整 panel 不渲染。

固定文案(对齐 `FairnessCalculator` 实际实现):
- **Gini**:等待时间分布的 Lorenz 曲线下方面积偏离对角线程度。0 = 完全公平,1 = 极端不公平。party-weighted 样本展开。
- **非打包窗口负载 CV**:stddev / mean(总体方差,N 为分母),对 `windowTypes != "TAKEAWAY"` 的所有窗口计算。与 `window_choice_metrics.window_served_count_cv`(POPULAR+NORMAL+COLD 子集)是不同口径。
- **跨角色差异**:solo dine-in / group dine-in / takeaway 三类的 party-weighted **中位数**等待时间 max − min(分钟)。每类 weighted 样本 < 5 跳过,可用类别 < 2 时返回 0。

### 2.4 `BottleneckDiagnosisPanel`

文件:`sun/src/components/BottleneckDiagnosisPanel.jsx`,入参 `{ summary }`。

**三条渲染分支**:

**(A) `summary.bottleneck_diagnosis == null` 防御路径**(理论上不会出现 — `BottleneckAnalyzer` 保证非 null,即使输入全空也回 BALANCED;此分支仅守后端意外回退):整个 panel 不渲染。

**(B) BALANCED 路径**(`primary == "balanced"`):绿色 banner "✓ 无明显瓶颈" + 单格绿色 severity-bar + 简短说明 "所有 4 类资源利用率均 < 0.85,系统处于均衡状态"。

**(C) 触发路径**:`primary` 卡片(红/橙/黄)+ optional `secondary` 卡片 + evidence 表(`type / severity / metric_name / observed_value / threshold / window_id`)。`bottlenecks[]` 数组按后端排序顺序直接渲染(severity desc + enum 序)。

**enum 大小写兼容**(防御后端意外回退):

```js
const primary = String(diagnosis.primary || '').toLowerCase()
const isBalanced = primary === 'balanced'
const styleFor = sev => SEVERITY_STYLE[String(sev || '').toLowerCase()] || SEVERITY_STYLE.balanced
```

**类型中文映射**:`window_service_capacity → 窗口服务能力`、`seat_capacity → 座位容量`、`takeaway_capacity → 打包窗口`、`arrival_surge → 到达冲击`、`balanced → 无瓶颈`。

**严重度色卡**:HIGH 红 / MEDIUM 橙 / LOW 黄 / BALANCED 绿。

**`windowId` 显示规则**(后端 0-based 已确认 `BottleneckAnalyzer.java:93,150` `maxIdx = i`):

```
windowId === -1  → "—"
windowId >= 0    → "窗口 #" + windowId        ← 直接显示后端值,不擅自 +1
```

雷达图本轮**不做**(决策保留)。

### 2.5 `AnalysisPage` 集成位置

```
KPI 5 卡 → 结论摘要 → WaitTimePanel →
★ BottleneckDiagnosisPanel → ★ WaitExperienceProxyPanel → ★ FairnessPanel →
WindowChoiceMetricsCard → AdvancedStatsPanel → 打包决策表 → 参数复盘 → 趋势图
```

3 个 panel 插在 `WaitTimePanel` 之后、`WindowChoiceMetricsCard` 之前。空态时整 panel 不渲染,不留空白占位。

---

## 3. PR 计划

单一 PR:`feature/pr-a-rfc-011-012-display`。

### 3.1 文件清单

| 文件 | 状态 | 行数(估) |
|---|---|---|
| `sun/src/components/WaitExperienceProxyPanel.jsx` | 新增 | ~80 |
| `sun/src/components/FairnessPanel.jsx` | 新增 | ~120 |
| `sun/src/components/BottleneckDiagnosisPanel.jsx` | 新增 | ~150 |
| `sun/src/components/WaitExperienceProxyPanel.test.jsx` | 新增 | ~80(3 用例) |
| `sun/src/components/FairnessPanel.test.jsx` | 新增 | ~110(4 用例) |
| `sun/src/components/BottleneckDiagnosisPanel.test.jsx` | 新增 | ~150(6 用例) |
| `sun/src/pages/AnalysisPage.jsx` | 微改 | +6 |
| `sun/src/pages/AnalysisPage.test.jsx` | 微改 | +1 用例 |
| `USER_GUIDE.md` | 微改 | +1 段落 |

**3 新组件 + 3 新测试 + 2 微改前端 + 1 文档 = 9 文件**。后端 0 文件。

### 3.2 提交结构(单 PR 内 2 commit)

- **Commit 1**:`feat(frontend): add RFC-011/012 panels (WaitExperienceProxy/Fairness/BottleneckDiagnosis)`
  - 3 新组件 + 3 测试,**不做集成**(组件存在但未挂)
- **Commit 2**:`feat(frontend): mount RFC-011/012 panels into AnalysisPage + USER_GUIDE`
  - `AnalysisPage` 集成(import + 3 行 JSX)
  - `AnalysisPage.test.jsx` +1 用例
  - `USER_GUIDE.md` +1 段落

每 commit 独立绿,便于 `git revert` 单点回滚。

---

## 4. 测试计划

### 4.1 用例分布

| 文件 | 用例 | 关键覆盖 |
|---|---|---|
| `WaitExperienceProxyPanel.test.jsx` | 3 | 6 项全展示 / null 整 panel 不渲染 / snake-camel 等价 |
| `FairnessPanel.test.jsx` | 4 | 4 项全展示 / null 不渲染 / Gini 阈值上色边界 / cross_role_fairness 字段绑定 |
| `BottleneckDiagnosisPanel.test.jsx` | 6 | null 防御 / BALANCED / 单触发 / 双触发 / `windowId=-1 → "—"` / enum BALANCED 与 balanced 等价 |
| `AnalysisPage.test.jsx` | +1 | 3 panel 都被挂上 |

**合计 +14 vitest**。

### 4.2 基线影响

| 层 | baseline | 本 PR 后 |
|---|---|---|
| 后端 `mvn test` | 415 | **415 不变**(0 改) |
| 前端 `npm test` | 62 | **76**(+14) |

### 4.3 关键测试细节

**FairnessPanel Gini 阈值边界**(对应 §2.3 上色规则):
- `wait_gini = 0.19` → 绿
- `wait_gini = 0.20` → 黄(下界含)
- `wait_gini = 0.39` → 黄
- `wait_gini = 0.40` → 红(下界含)

**BottleneckDiagnosisPanel enum 大小写**:
- 同 fixture 跑两遍,一遍 `primary: "BALANCED"` 一遍 `primary: "balanced"` → 渲染输出等价

**`windowId` 边界**:
- `windowId = -1` → DOM 文本 "—"
- `windowId = 0` → DOM 文本 "窗口 #0"(不变 +1)
- `windowId = 7` → DOM 文本 "窗口 #7"

---

## 5. USER_GUIDE.md 段落

在"分析页"或"报告解读"现有章节后追加(具体插入位置由实施时找最合适位置):

```markdown
### 启发式分析指标

报告中的等待体验代理(综合代理指数 / P/I 比 / 不确定性 / 焦虑压力 /
独食调整)、公平性(等待 GINI / 非打包窗口负载 CV / 跨角色差异)与
瓶颈诊断(窗口服务能力 / 座位容量 / 打包窗口 / 到达冲击 / 无瓶颈),
均为基于 SimulationSummary 已有字段的派生启发式指标,**仅用于同
模型内相对比较**(如不同配置间 A/B 对照),不解释为真实感知等待
时间或绝对优劣判断。样本不足(< 50)时,等待体验代理与公平性
不展示。
```

---

## 6. 验收清单

PR 合入前以下全部 ✅ 才算交付:

### 6.1 自动化测试

- [ ] `cd src_24281231 && mvn -DskipFrontend=true test` → **415 全绿**(0 改)
- [ ] `cd src_24281231/sun && npm test -- --run` → **76 全绿**
- [ ] `cd src_24281231 && npm run build:backend` EXIT 0(同时不破 `ServedFrontendBundleFreshnessTest`)

### 6.2 浏览器手动验证(3 个路径必须各跑一次)

- [ ] **BALANCED**:小流量配置(arrivalRate=30, windowCount=10)→ 绿色 banner "✓ 无明显瓶颈"
- [ ] **触发**:大流量配置(arrivalRate=180, windowCount=4, totalSeats=20)→ primary 红/橙/黄卡片 + evidence 表
- [ ] **null 防御**:duration=0.05(< 50 样本)→ WaitExperienceProxy + Fairness panel 整体不渲染,Bottleneck 仍为 BALANCED 渲染

### 6.3 后端 0 改动硬验证

- [ ] `git diff --stat origin/claude...HEAD -- src/main/java` 输出**空**
- [ ] `git diff --stat origin/claude...HEAD -- src/test/java` 输出**空**
- [ ] `git diff --stat origin/claude...HEAD -- pom.xml` 输出**空**
- [ ] `git diff --stat origin/claude...HEAD -- 'src/main/resources/**'` 输出**空**(除 frontend bundle 重建产物)

### 6.4 依赖 0 引入

- [ ] `git diff origin/claude...HEAD -- sun/package.json` 仅 `dependencies` / `devDependencies` 段无新增条目(本 PR 不应有 npm 包变化)
- [ ] `package-lock.json` 若变化,仅来自 `npm install` 走过场的 lockfile 元数据,不引入新的 top-level 包

### 6.5 文档与功能

- [ ] `USER_GUIDE.md` "启发式分析指标" 段落上线,3 类指标各点名,含"同模型内相对比较"措辞
- [ ] AnalysisPage 3 panel 顺序与 §2.5 一致
- [ ] 红线 0 触碰:不动 RFC-010A/B/C / RFC-011 / RFC-012 service / calculator / DTO,不引入后端 endpoint,不动顶部导航

---

## 7. 风险

| # | 风险 | 等级 | 缓解 |
|---|---|---|---|
| 1 | RFC-011 sub-DTO null 路径前端线上未验证 | 低 | 单测显式构造 null;§6.2 浏览器跑 duration=0.05 触发 |
| 2 | Bundle 重建破 `ServedFrontendBundleFreshnessTest` | 低 | PR 提交前必须 `npm run build:backend`,与现有 PR 流程一致 |
| 3 | 中文文案 / Tailwind class 与现有 panel 风格不一致 | 低 | 参照 `WindowChoiceMetricsCard` 现有实现 |
| 4 | windowId 显示出错(误 +1)| 低 | T-bn-5 单测显式守 `windowId=0 → "窗口 #0"` |
| 5 | enum 大小写在线上回退 | 低 | T-bn-6 单测同 fixture 跑大小写两路径 |
| 6 | 用户后续要求加回 BatchScanPage | 低 | 本 spec 已声明 RFC-010 productization 为 future work,需独立 RFC |

---

## 8. 不做事项再确认

- 不实现:SSE / batch endpoint / sensitivity endpoint / `AnalysisTaskService` / `reports/batch/` 落盘 / `BatchScanPage` / 顶部导航入口 / 雷达图 / 任何后端改动
- RFC-010A/B/C 后端能力**保留**为 future work;若后续需 productization,独立 RFC + 独立 spec

---

## 附录 A:关键代码位置参考

- 已落地后端(本 PR **不动**):
  - `src/main/java/com/bjtu/simulation/service/{BottleneckAnalyzer,WaitExperienceProxyCalculator,FairnessCalculator}.java`
  - `src/main/java/com/bjtu/simulation/dto/{BottleneckDiagnosis,WaitExperienceProxyMetrics,FairnessMetrics,DetectedBottleneck,BottleneckEvidence,BottleneckType,BottleneckSeverity}.java`
- 前端复用基础(本 PR **不动**):
  - `sun/src/utils/simulation.js`(`read` helper)
  - `sun/src/pages/AnalysisPage.jsx`(集成点,微改)
  - `sun/src/components/WindowChoiceMetricsCard.jsx`(panel 风格参考)

## 附录 B:用户裁决记录(brainstorming session)

### v1/v2(已废弃,2026-05-24 上午)

- 范围:全包(后端 endpoints + 前端 panel + 前端 batch 页)
- IA:方案 B(分析页加 panel,批量独立页)
- 调用模式:异步 + polling
- Seeds 上限:Batch ≤ 50,Sensitivity estimatedRuns ≤ 200
- 报告落盘:reports/batch/、reports/sensitivity/
- RFC-011 布局:两个完整 panel
- Batch 结果密度:只显 aggregate
- RFC-012 形态:文字 + 色条(雷达图明确不做)

### v3 收缩(本版,2026-05-24 下午)

- **范围收缩理由**:课程大作业范围控制,避免从"食堂仿真与可视化系统"偏离到"仿真实验平台"
- **保留**:仅 PR-A 前端 3 panel + USER_GUIDE 段落
- **删除**:PR-B / PR-C / PR-D(后端 endpoints + AnalysisTaskService + BatchScanPage + 顶部导航)
- **RFC-010A/B/C 定位**:后端实验能力 + future work,不做产品化展示
- 测试增量从 +87(30+20+13+24)收缩为 **+14**(单 PR-A vitest)

---

**End of Spec v3.**
