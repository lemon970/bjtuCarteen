# CLEANUP_REPORT.md

> 阶段 5 最终交付报告。本次清洗以 `cleanup/remove-dead-code` 分支为载体,
> 从 `claude` 分支(commit `9c984fa`)派生,共 **3 个 commit**。

---

## 1. 总览

```
9c984fa (claude)                            ← 基线
├── 68e6864 PR-CLEAN-1 build/run artifacts  ← 64 文件移出索引
├── 44b535e CLEANUP_AUDIT.md + PLAN.md      ← 阶段 1+2 文档
└── 63327b2 PR-CLEAN-4 obsolete docs        ← 6 文档删除 + 1 文档更新
```

| 指标 | 基线 | 现状 | 变化 |
|---|---|---|---|
| 跟踪文件数(`git ls-files`) | 401 | 335 | **-66** |
| 后端测试 | 468 / 0F / 0E | 468 / 0F / 0E | 不变 |
| 前端测试 | 12 文件 / 62 用例 | 12 文件 / 62 用例 | 不变 |
| 前端 build | EXIT 0 (611 modules) | EXIT 0 (611 modules) | 不变 |
| diff shortstat(基线..HEAD)| — | **73 files changed, 696 insertions, 3053 deletions** | — |

业务逻辑、测试、API 契约**零修改**。仅删除构建产物、运行产物副本和 6 份历史文档。

---

## 2. 删除统计

### 2.1 PR-CLEAN-1:构建 / 运行产物

| 项 | 数量 | 备注 |
|---|---|---|
| 移出索引(git rm --cached)| **63 文件** | `target/**` 59 + `static/frontend/**` 3 + `static/index.html` 1 |
| `.gitignore` 新增规则 | 2 行 | `/src/main/resources/static/frontend/`、`/src/main/resources/static/index.html` |
| 物理删除目录 | `target/`、`reports/`、`analysis-store/`、`sun/dist/`、`dataAnalyze/.vs/`、`dataAnalyze/Project3/x64/` | 测试 / build 后部分目录会被自动重建 |
| 一次性磁盘释放 | ~870 MB | `reports/` 本身就 777 MB |

> **注**:`target/` 和 `reports/` 在跑过 `mvn test` 后会再次出现在工作树中,但**不会再被跟踪**(.gitignore 已经覆盖)。

### 2.2 PR-CLEAN-4:历史 / 重叠文档

| 文件 | 删除原因 |
|---|---|
| `IMPLEMENTATION_SPLIT.md` | "Final Phase" 四人协作产出对应,一次性历史阶段记录 |
| `FRONTEND_REFACTOR_REPORT.md` | 前端中文多界面重构与接口对齐说明,一次性重构报告 |
| `API_HANDOFF.md` | 接口交接文档,与 `API.md` 主题完全重叠 |
| `INTEGRATION.md` | 项目整合说明,与 `README.md` 启动 / 整合段落重叠 |
| `docs/analysis/rfc-002-historical-diagnostics.md` | RFC-002 设计稿,代码 `HistoricalDiagnosticsService` 已落地 + 2 测试类锁定 |
| `docs/analysis/rfc-003-historical-quality-score.md` | RFC-003 设计稿,代码 `HistoricalQualityScorer` 已落地 + 2 测试类锁定 |

`源代码说明文档.md`:同步删除两行对 `API_HANDOFF.md` 和 `IMPLEMENTATION_SPLIT.md` 的引用条目(clean.txt §3.2#10 红线)。

### 2.3 合计

| 项目 | 数量 |
|---|---|
| 删除文件数(从索引)| **69**(63 build/run + 6 文档)|
| 新增文件数 | **3**(`docs/cleanup/CLEANUP_AUDIT.md`、`CLEANUP_PLAN.md`、`CLEANUP_REPORT.md`)|
| 净跟踪文件变化 | -66 |
| 删除代码行数 | **3053**(几乎全是 .class 二进制 + 历史 .md)|
| 新增代码行数 | **696**(全部为本次清洗文档)|
| 后端 / 前端源码改动 | **0** |
| 测试改动 | **0** |
| 业务行为改动 | **0** |
| 仓库体积 | 工作树减 ~870 MB(主要是 `reports/`)|

---

## 3. 删除分类

| 类别 | 删除内容 |
|---|---|
| 构建产物(target/) | 50 .class、application.yml、static/{demo,index,frontend}.html、maven-status `*.lst`、test-classes `*.class` |
| 运行产物 | 物理删除 `reports/`、`analysis-store/`、`sun/dist/`、`dataAnalyze/.vs/`、`dataAnalyze/Project3/x64/` |
| 前端 build 产物 | `static/frontend/index.html`、`assets/index-BEfG-hW-.css`、`assets/index-DaHL9keg.js`、`static/index.html` |
| 后端废弃代码 | **0**(审计未发现有引用证据的死代码 — 见 §5)|
| 前端废弃代码 | **0**(App.jsx 引用图覆盖 21 jsx + 5 utils 全部源)|
| C++ 产物 | 物理删除 `dataAnalyze/.vs/`、`dataAnalyze/Project3/x64/`(已 gitignore)|
| 废弃文档 | 4 份根目录 .md(handoff / split / refactor / integration)+ 2 份已落地 RFC 设计稿 |
| 废弃测试 | **0**(全部测试都对应活源)|

---

## 4. 保留理由(看似旧但保留)

| 保留对象 | 当前引用 / 用途 | 后续是否可继续收敛 |
|---|---|---|
| `PerSeedMode.FULL_REPORTS_DEBUG` enum + `BatchRunService` 抛 UOE | 主动 fail-fast,javadoc 标记为"留待后续 RFC";schema 序列化兼容性需要 | 待独立 RFC |
| `engine/SimulationEngine.java` 中 `WORKLOAD_ROUTING / HYBRID_OVERFLOW` UOE | RFC-009 v2 占位枚举(主动 fail-fast)| 待独立 RFC |
| `ReportSummaryStore.compactSummaryStore / fullResetSummaryStore` UOE | "phase 1 disabled"占位,被 `ReportSummaryStoreTest` 锁住 | 待独立 RFC |
| `dataAnalyze/DiningSimulation.{cpp,h}` (~870 行 C++) | 仅 `--mode=simulate` 路径用,Java 从未调用;但属 C++ 子工程,删除会牵动 vcxproj/CMakeLists | **暂缓**(见 §5.C)|
| `源代码说明文档.md` | 答辩 / 提交需求(组员贡献度一致性)| 见 §5.E |
| `samples/curated-history/`(81 文件)+ `CuratedSamplesGenerator.java` | 无运行时 reader,但可能是答辩用历史 baseline | **暂缓**(见 §5.B)|
| `SimulationOptimizationController` 同步 `/optimize` 端点 | 前端不调用,仅 1 个集成测试覆盖;maintainer javadoc 注明"字面 0 改动以保留同步路径" | **暂缓**(见 §5.A)|
| `dataAnalyze/Project3.vcxproj.user` | (审计中误以为已跟踪;实际未跟踪)| — |

---

## 5. 暂缓项(等待用户裁决)

| 编号 | 暂缓项 | 暂缓原因 | 后续处理建议 | 是否单独 PR |
|---|---|---|---|---|
| **A** | Sync `/api/simulation/optimize` controller + service + 2 测试用例 | 前端零调用,但 maintainer 在 javadoc 中**主动表明保留**作为同步路径;删除会触发 clean.txt §3.2#5"修改业务行为"红线 | 用户明确该端点是否废弃 → 若是,删除 sync controller + sync test;`OptimizationService` 仍被异步路径用,保留 | ✅ 单独 PR |
| **B** | `samples/curated-history/`(81 跟踪文件)+ `CuratedSamplesGenerator.java` | 无 runtime reader(grep 0),但可能是答辩用历史比对数据集 | 用户裁决:仅开发产物 → 删除;答辩 / 比对必需 → 保留 | ✅ 单独 PR |
| **C** | `dataAnalyze/DiningSimulation.{cpp,h}` 与 `源.cpp` 中 `--mode=simulate` 分支 | 上一轮 rebuild.txt audit 已审为"事实生产路径死代码";删除影响 vcxproj/CMakeLists 跨工具链 | 用户对照 rebuild.txt 的 Scope B 决定 | ✅ 单独 PR |
| **D** | `Pr9b/c/d/e` 4 个 RFC 测试类合并为 `QueueChoiceModelTest` 等 | 单纯重命名,RFC 编号是定位 PR / 设计稿的索引,合并丢失映射;clean.txt §8.5 自身警告与本轮清洗同 PR 无价值 | 不建议本轮做。留作未来"测试套整理 RFC" | ❌ 不建议 |
| **E** | `源代码说明文档.md` | 与 `ARCHITECTURE.md` 部分重叠,但作为答辩 / 交付物可能必需 | 用户裁决是否答辩需要 → 是,保留;否,删除 | ✅ 单独 PR(若决定删)|

`docs/cleanup/CLEANUP_PLAN.md §6` 中已列出每项的具体证据与影响。

---

## 6. 测试结果

### 6.1 后端

```
$ mvn -DskipFrontend=true test
...
[INFO] Tests run: 468, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 6.2 前端

```
$ cd sun && npm test -- --run

 Test Files  12 passed (12)
      Tests  62 passed (62)
   Duration  8.71s
```

### 6.3 前端打包

```
$ cd sun && npm run build:backend

vite v5.4.21 building for production...
✓ 611 modules transformed.
../src/main/resources/static/frontend/index.html                  0.43 kB │ gzip:   0.29 kB
../src/main/resources/static/frontend/assets/index-BEfG-hW-.css  36.93 kB │ gzip:   6.68 kB
../src/main/resources/static/frontend/assets/index-DaHL9keg.js  775.04 kB │ gzip: 254.34 kB
✓ built in 4.54s
```

### 6.4 跟踪状态

```
$ git ls-files | grep -E '(^target/|\.class$|\.obj$|\.exe$|\.log$|\.pid$|/x64/|/Debug/|/Release/|simulation_report_.*\.json|simulation_result_report_.*\.json)' || echo "OK_CLEAN"
OK_CLEAN
```

```
$ git status --short
 M .vscode/settings.json
```

> 仅余 `.vscode/settings.json`:本会话开始前就存在的本地修改,**与本次清洗无关**,故不纳入提交。

---

## 7. 风险说明

### 7.1 已规避的风险

| 风险 | 缓解 |
|---|---|
| `static/frontend/` 缺失会让 `ServedFrontendBundleFreshnessTest` 失败 | PR-CLEAN-1 后**先**跑 `npm run build:backend` 重建,再跑 `mvn test`(已验证 468/0/0)|
| 删除文档但其他文档仍引用 | `源代码说明文档.md` 的 2 行同步删除,clean.txt §3.2#10 红线已守 |
| `target/` 突然被重建后再次被 commit | 新 .gitignore 在 `git add` 时拦截;手动 `git status --short` 抽样 0 命中 |
| 业务行为改变 | 0 后端 / 前端 / 测试代码改动 → 业务行为不可能改变 |

### 7.2 残留风险

| 风险 | 影响 | 监测 |
|---|---|---|
| 同步 optimize 端点未来需要时找不到测试覆盖 | 低(测试还在,未删) | §5.A 暂缓中,等用户裁决 |
| `samples/curated-history/` 在未来某个 RFC 重新启用时已删 | 中(若用户在 §5.B 选择删除)| 暂缓,未删除 |
| 历史文档(`IMPLEMENTATION_SPLIT.md`、`API_HANDOFF.md`)中的某段事实信息丢失 | 低(`API.md` / `README.md` / `ARCHITECTURE.md` / `源代码说明文档.md` 已覆盖)| `git show 9c984fa:API_HANDOFF.md` 仍可恢复 |
| 自动 mvn build 时 frontend-maven-plugin 与新 .gitignore 交互 | 低(.gitignore 不影响 build 输出生成,只影响 git 跟踪)| 已验证 `npm run build:backend` 重建后 `git status` 干净 |

### 7.3 回滚方式

```bash
# 整轮回滚(扔掉本分支)
git checkout claude
git branch -D cleanup/remove-dead-code

# 单 PR 回滚(保留其他)
git revert 63327b2   # 撤回 PR-CLEAN-4(文档恢复)
git revert 68e6864   # 撤回 PR-CLEAN-1(产物恢复跟踪;但本地物理已删,需 rebuild)
git revert 44b535e   # 撤回 cleanup 文档(很少需要)

# 单文档恢复
git show 9c984fa:IMPLEMENTATION_SPLIT.md > IMPLEMENTATION_SPLIT.md
```

3 个 commit 都是普通 commit(无 force push、无 amend、无 history rewrite),回滚风险极低。

---

## 8. 后续仍可继续收敛的模块

1. §5.A — sync optimize 路径裁决
2. §5.B — `samples/curated-history/` 裁决
3. §5.C — `dataAnalyze/DiningSimulation.{cpp,h}` 与 rebuild.txt Scope B 联动
4. §5.E — `源代码说明文档.md` 是否答辩必需
5. dataAnalyze 自身(rebuild.txt 上一轮已经审过,本轮未碰)
6. 测试套按 RFC 编号合并(收益小,不建议本轮做)

每项都已在 `CLEANUP_PLAN.md §6` 与本报告 §5 列出具体引用证据,可由用户直接拾起后续 PR。

---

## 9. 红线自检(最终)

| clean.txt §3.2 红线 | 本次清洗触发? | 如何守住 |
|---|---|---|
| #1 无引用分析的删除生产代码 | ❌ | 全部候选有 grep 证据;暂缓 5 项等用户裁决 |
| #2 为旧测试保留废弃代码 | ❌ | 仅删了对应文档,无代码 / 测试改动 |
| #3 继续新增功能 | ❌ | 仅清洗,零新功能 |
| #4 重写核心仿真逻辑 | ❌ | 仿真代码 0 改 |
| #5 修改业务行为 | ❌ | 0 后端 / 前端 / 测试改动 |
| #6 把构建产物重新提交 | ❌ | 反向(从跟踪移出);新 .gitignore 守 |
| #7 保留"可能以后有用" | ❌ | 5 项 P3 暂缓显式摆给用户 |
| #8 移到 legacy 假装清理 | ❌ | 真删,不挪 |
| #9 用 TODO 代替删除 | ❌ | |
| #10 文档继续描述已删功能 | ❌ | `源代码说明文档.md` 同步更新 |
| #11 一次性巨改 | ❌ | 3 commit,每个独立可 revert |
| #12 跳过最终测试 | ❌ | 每 PR 后跑 mvn + npm test;最终验收三项命令全跑 |

---

## 10. 交付清单

```
docs/cleanup/CLEANUP_AUDIT.md       (commit 44b535e, 422 行)
docs/cleanup/CLEANUP_PLAN.md        (commit 44b535e, 270 行)
docs/cleanup/CLEANUP_REPORT.md      (本文件)
```

3 commit(`68e6864 → 44b535e → 63327b2`)位于分支 `cleanup/remove-dead-code`。
基线分支 `claude` 未被修改。
