# RFC-002 阶段 2:Historical Diagnostics(只设计,不写代码)

> 本轮仅出设计,不创建/修改任何 `.java` 或 `.jsx` 文件,不改 `application.yml` 或 `pom.xml`。
> 实施在用户单独批准后进行。

---

## 一、背景

阶段 1 (`RFC-001` / 计划文件 `ai-polished-dewdrop.md`) 已交付 `ReportSummaryStore`:

- `analysis-store/report-summaries/{reportId}.summary.json` 长期保留小摘要(≤ 5 KB)。
- 与 `reports/` 物理隔离,清理 `reports/` 不影响摘要。
- 24+183 测试全绿,`SimulationReportRepository.write` 末尾以非阻塞方式触发 `summaryStore.upsert`。

但摘要数据当前**没有任何消费者**。阶段 2 在这个数据基础上引入"对比当前报告与历史摘要的诊断输出",**只输出事实(facts/checks/anomalies/warnings),不做综合评分**。

---

## 二、目标

1. 给定一个 `reportId`,基于 `analysis-store/report-summaries/` 中的摘要,产出一份 `historical_diagnostics` JSON 子树:
   - `basis`:语料规模、可用样本、匹配策略、source_status 分布。
   - `checks`:结构化检查项(如 `INSUFFICIENT_BASELINE` / `MISSING_SUMMARY` / `INVARIANT_FAILURE`)。
   - `anomalies`:核心指标相对历史中位数的偏离(robust z 基于 MAD)。
   - `warnings`:其余非阻断性提示(如 `MAD_ZERO`、`STALE_NEIGHBORS`)。
2. 只读摘要,不读完整 `reports/*.json`,不调 C++,不调 `InternalStatisticsAnalyzer`。
3. **可选启用**:请求参数控制,默认关闭;关闭时 `/api/analysis/run` 旧响应一字节不变。
4. 摘要存储不可用 / 不完整时主分析路径不受影响。

## 三、非目标

- ❌ 综合 `quality_score`(0–100 或其它分数)。
- ❌ 等级化输出(`excellent` / `good` / `poor` / `tier`)。
- ❌ 任何前端可视化或新页面。
- ❌ 修改 `AnalysisCore.cpp` / `源.cpp` / 任何 C++ binary。
- ❌ 修改 `InternalStatisticsAnalyzer`(Java fallback)。
- ❌ 修改 `ReportSummaryExtractor` 抽取逻辑(除非诊断需要的字段在阶段 1 schema 中确实缺失)。
- ❌ 引入数据库 / SQLite / 任何索引中间件 / k-NN 库。
- ❌ 改 `batch-analyze` 协议 / `/api/analysis/cross-scenario` 默认行为。
- ❌ 自动定时任务、自动 compact / cleanup。
- ❌ 扫 `reports/*.json` / 重读源报告。

---

## 四、数据来源

### 4.1 允许使用

| 来源 | 用途 |
|---|---|
| `ReportSummaryStore.read(reportId)` | 取当前报告对应摘要 |
| `ReportSummaryStore.list()` | 取所有摘要(已 sorted by filename,损坏自动跳过) |
| `analysis-store/report-summaries/*.summary.json` | 同上,通过 store 间接读取 |

### 4.2 禁止使用

- `reports/*.json` / `simulation-history-*.json` / `simulation-report-latest.json` —— 任何对完整报告的 `readTree` 或 streaming 解析。
- `dataAnalyze/bin/*` —— 不调 C++。
- `InternalStatisticsAnalyzer` —— 不重新计算指标。
- 数据库 / 文件锁 / 跨进程通信 —— 只读 store。

诊断逻辑必须能在 `analysis-store/` 唯一存在、`reports/` 已被清空的情况下完整工作(对应阶段 1 测试 T15/T16/T21)。

---

## 五、匹配策略(neighbor selection)

匹配的目的是从语料里挑出"配置可比"的历史摘要作为基线。**严格分层、自动降级**,先到先得。

### 5.1 候选池基础过滤

候选池 = `list()` 全部摘要中,满足以下条件者:

1. `precheck.parse_status != "failed"`(parse 失败的占位摘要绝不进基线)。
2. `report_id != currentReportId`(自己不和自己比)。
3. `precheck.basic_invariants_valid == true`(违反基本不变量的摘要不可信)。

不变量违规摘要被排除时,在 `basis.excluded_counts.invariant_failed` 中显式计数。

### 5.2 source_status 参与策略

| status | 是否参与基线 | 备注 |
|---|---|---|
| `present` | ✅ 参与 | 数据可信,无附加 warning |
| `stale` | ✅ 参与 | 摘要数据本身仍完整;但若**所有**邻居均为 stale,在 `warnings` 加 `STALE_NEIGHBORS` |
| `missing` | ✅ 参与 | 摘要数据由阶段 1 保留,可信度 = 摘要落盘时的可信度;在 `warnings` 加 `MISSING_SOURCE_NEIGHBORS` 当占比 > 50% |
| `deleted` | ✅ 参与 | 与 missing 同语义,合并计入 `MISSING_SOURCE_NEIGHBORS` |
| `unverified` | ✅ 参与 | 合并计入 `MISSING_SOURCE_NEIGHBORS` |

理由:摘要本身就是为了"源被清理后仍可用"而设计(阶段 1 §2.3 问题 1)。把 missing/stale 排除在外会让最常见场景下样本归零。

例外:用户显式传 `historicalDiagnosticsStrict=true`(本阶段**不**实现,仅在 schema 上保留 `basis.policy.strict=false`,留作阶段 3+ 钩子)。

### 5.3 匹配阶梯

**当前报告的 summary** 决定从哪一层开始:

```
Tier A: scenario_id exact
  条件: 当前 summary.report_meta.scenario_id 非 null 且非空字符串
  邻居筛选: candidate.report_meta.scenario_id == current.scenario_id

Tier B: config_fingerprint exact
  条件: 当前 summary.config.config_fingerprint != "sha1:unavailable"
  邻居筛选: candidate.config.config_fingerprint == current.config.config_fingerprint

Tier C: similar_config (近邻匹配)
  邻居筛选 (全部满足):
    - same: window_count, total_seats, takeaway_window_count, weather_type
    - within ±10%: arrival_rate, duration
    - within ±0.05 abs: pack_probability
    - 任何 null/不可比字段:跳过该候选
```

### 5.4 选层规则

```
candidates_A = filter Tier A
candidates_B = filter Tier B
candidates_C = filter Tier C

if size(candidates_A) >= 3: use A, strategy="scenario_id_exact"
elif size(candidates_B) >= 3: use B, strategy="config_fingerprint"
elif size(candidates_C) >= 3: use C, strategy="similar_config"
else:
  # 取**最高**层中能给出至少 1 条的结果(允许 1~2 条邻居),不再降级
  if size(candidates_A) > 0: use A
  elif size(candidates_B) > 0: use B
  elif size(candidates_C) > 0: use C
  else: matched=[], strategy="none"
```

`basis.matching_strategy ∈ {"scenario_id_exact", "config_fingerprint", "similar_config", "none"}`。
若邻居数 < 3,产生 `INSUFFICIENT_BASELINE` warning(见 §六)。

### 5.5 当前 report 的边界

| 当前 report 状态 | 行为 |
|---|---|
| 当前 reportId 在 store 中**没有** summary | `checks` 加 `MISSING_SUMMARY` (severity=error),不做匹配/偏离;`basis.matched_reports=0`;主分析照常返回 |
| 当前 summary `parse_status=failed` | `checks` 加 `CURRENT_PARSE_FAILED`;不做偏离;仍输出 `basis.corpus_size` 等语料事实 |
| 当前 summary `basic_invariants_valid=false` | `checks` 加 `INVARIANT_FAILURE`(error,带 violations 列表);**仍**做偏离判断(用户可自己决定是否信) |
| 当前 summary `source_status ∈ {missing,deleted}` | `warnings` 加 `CURRENT_SOURCE_MISSING`;不影响匹配 |
| 当前 summary 缺 `scenario_id` 与 `config_fingerprint` | 跳过 Tier A/B,直接走 Tier C |
| 当前 summary 缺 `similar_config` 所需字段(如 `window_count=null`) | Tier C 也无法计算,降级 `strategy="none"`,加 `warnings: SIMILAR_CONFIG_UNAVAILABLE` |

---

## 六、指标偏离算法

### 6.1 监测的指标(只用阶段 1 摘要中已抽取字段)

| metric | 类型 | 缺失策略 |
|---|---|---|
| `abandonment_rate` | rate ∈ [0,1] | 跳过单个 metric,加 `warnings: METRIC_MISSING:abandonment_rate` |
| `avg_wait_time_minutes` | minutes ≥ 0 | 同上 |
| `typical_wait_time_minutes` | minutes ≥ 0 | 同上 |
| `seat_utilization_rate` | rate ∈ [0,1] | 同上 |
| `takeaway_rate` | rate ∈ [0,1] | 同上 |
| `max_total_queue_size` | int ≥ 0 | 同上 |
| `avg_total_queue_size` | float ≥ 0 | 同上 |

任意被监测 metric 当前值为 null 时,该 metric 完全跳过,不构成 anomaly,不构成 check error。

### 6.2 算法(对每个 metric 独立)

设邻居中有效样本(非 null)数为 `n`,邻居取值为 `xs`,当前值为 `cur`。

```
n < 3:
  跳过该 metric 的偏离判断
  不输出该 metric 的 anomalies
  不输出该 metric 的 historical_median

3 <= n <= 4:
  median = median(xs)
  不计算 MAD
  不判 outlier
  输出 historical_median 字段(供前端/调用方对照),anomaly 不产生

n >= 5:
  median = median(xs)
  MAD = median(|x_i - median|)
  if MAD == 0:
    warnings 追加 "MAD_ZERO:<metric>"
    输出 historical_median,robust_z = null,severity = "info"
  else:
    robust_z = 0.6745 * (cur - median) / MAD   (常数把 MAD 标定到 σ 等价)
    severity:
      |robust_z| >= 3 → "warning"
      2 <= |robust_z| < 3 → "info"
      |robust_z| < 2 → 不产生 anomaly 项
```

**注意**:阶段 2 的 `severity` 只在 anomaly 项内部使用,**不**作为综合评分,**不**用于排序"质量等级"。`severity` 仅为消费方提供阈值提示。

### 6.3 数值约束

- 所有输出小数:保留 3 位(`Math.round(x * 1000) / 1000`)。
- `robust_z` 输出 2 位。
- 任何 NaN / Infinity → 输出 null + warning。

---

## 七、JSON Schema

### 7.1 完整结构(启用时)

```json
{
  "historical_diagnostics": {
    "enabled": true,
    "schema_version": "1.0",
    "computed_by": "java-summary-store",
    "computed_at_epoch_millis": 1747641600000,

    "basis": {
      "summary_store_path": "analysis-store/report-summaries",
      "current_report_id": "abc123",
      "current_summary_present": true,
      "corpus_size": 120,
      "usable_summaries": 113,
      "matched_reports": 8,
      "matching_strategy": "scenario_id_exact",
      "self_excluded": true,
      "source_status_counts": {
        "present": 80,
        "stale": 2,
        "missing": 30,
        "deleted": 1,
        "unverified": 0
      },
      "excluded_counts": {
        "parse_failed": 4,
        "invariant_failed": 3,
        "self": 1
      },
      "policy": {
        "strict": false,
        "min_full_anomaly_n": 5,
        "min_median_only_n": 3,
        "robust_z_warning_threshold": 3.0,
        "robust_z_info_threshold": 2.0,
        "similar_config_window": {
          "arrival_rate_pct": 0.10,
          "duration_pct": 0.10,
          "pack_probability_abs": 0.05
        }
      }
    },

    "checks": [
      {
        "code": "MISSING_SUMMARY",
        "severity": "error",
        "message": "current report has no summary in analysis-store/report-summaries"
      },
      {
        "code": "INSUFFICIENT_BASELINE",
        "severity": "warning",
        "message": "matched_reports=2 < 5; deviation analysis skipped or median-only",
        "context": { "matched_reports": 2, "required_for_full": 5 }
      },
      {
        "code": "INVARIANT_FAILURE",
        "severity": "error",
        "message": "current summary failed basic invariants",
        "context": {
          "violations": ["served_count != dine_in_count + takeaway_count"]
        }
      }
    ],

    "anomalies": [
      {
        "metric": "avg_wait_time_minutes",
        "current": 12.4,
        "historical_median": 7.8,
        "mad": 1.2,
        "robust_z": 2.58,
        "severity": "info",
        "n": 8
      }
    ],

    "warnings": [
      "MAD_ZERO:takeaway_rate",
      "STALE_NEIGHBORS",
      "MISSING_SOURCE_NEIGHBORS",
      "METRIC_MISSING:typical_wait_time_minutes"
    ]
  }
}
```

### 7.2 字段语义约束

| 字段 | 必出 | 不可缺 |
|---|---|---|
| `enabled` | ✅ | true 时整子树存在;false 不出现整子树 |
| `schema_version` | ✅ | 固定 `"1.0"` |
| `computed_by` | ✅ | 固定 `"java-summary-store"` |
| `basis.*` | ✅ | 即使 `corpus_size=0` 也出 |
| `checks` | ✅ | 可空数组 |
| `anomalies` | ✅ | 可空数组(`matched_reports < 5` 时必空) |
| `warnings` | ✅ | 可空数组 |

### 7.3 与主响应的合并

`/api/analysis/run` 当前返回 `ApiResponse<JsonNode>`,`data` 字段为外部分析 JSON(`AnalysisResult.payload`)。

合并方式:
- 主分析 `payload` 是 `ObjectNode` 时,**追加**顶层键 `historical_diagnostics`。
- 主分析 `available=false`(503)时,仍把 `historical_diagnostics` 拼到那个降级 body 里,以便调用方在 C++ 不可用时仍能拿到诊断;**前提是请求显式启用**。
- 主分析 `payload` 不是 `ObjectNode`(理论上不会发生)时,跳过合并并加 `WARNING: payload_not_object`。

---

## 八、选择性启动

### 8.1 请求参数

扩展 `AnalysisController.RunRequest`:

```java
@JsonAlias({"include_historical_diagnostics", "includeHistoricalDiagnostics"})
private Boolean includeHistoricalDiagnostics;   // null/false 关闭
```

- 默认 `null`,语义同 `false`。
- 仅 `true` 时调用 `HistoricalDiagnosticsService`。
- 任何解析异常(参数类型错误)按 `false` 处理,不报 4xx。

### 8.2 不改 `/api/analysis/cross-scenario`

阶段 2 **不**为批量接口启用 `historical_diagnostics`(避免影响 `ScenarioRunServiceContractTest` 等基线契约)。需要时阶段 3 单独评估。

### 8.3 失败降级

`HistoricalDiagnosticsService` 内部任何异常 → 不抛,在 `historical_diagnostics` 子树内输出:

```json
{
  "historical_diagnostics": {
    "enabled": true,
    "schema_version": "1.0",
    "computed_by": "java-summary-store",
    "basis": { "corpus_size": 0, "matched_reports": 0, "matching_strategy": "none", ... },
    "checks": [ {"code": "DIAGNOSTICS_INTERNAL_ERROR", "severity": "error", "message": "<class>:<message>"} ],
    "anomalies": [],
    "warnings": []
  }
}
```

主分析 `payload` 不变。

---

## 九、实现文件范围(实施时)

### 9.1 新增

| 文件 | 职责 |
|---|---|
| `service/HistoricalDiagnosticsService.java` | 主服务:`diagnose(reportId)` 返回 `ObjectNode`(永不抛) |
| `service/HistoricalDiagnosticsService.java` 内部 record/类 | `Neighbor`、`MetricStats`、`MatchTier` 等小型不可变结构(避免引入新文件) |
| `test/.../service/HistoricalDiagnosticsServiceTest.java` | T1–T15 单元测试,使用 `@TempDir` 模拟 `analysis-store/` |
| `test/.../controller/AnalysisControllerHistoricalDiagnosticsTest.java`(可选,若 `AnalysisControllerIntegrationTest` 不便扩展) | 端到端启用 / 不启用对比 |

### 9.2 修改

| 文件 | 改动 |
|---|---|
| `controller/AnalysisController.java` | `RunRequest` 加可选字段;`runForReport` 在 wrap 后按需 merge |
| `service/ExternalAnalysisService.java` | **不改**(诊断在 controller 层合并,保持外部分析服务纯粹) |
| `controller/AnalysisControllerIntegrationTest.java` | 新增 1~2 个 case 验证默认关闭 / 启用产生 historical_diagnostics |

### 9.3 不修改

- `ReportSummaryStore.java`、`ReportSummaryExtractor.java`(阶段 1 数据层稳定)。
- `SimulationReportRepository.java`、`InternalStatisticsAnalyzer.java`、`ScenarioRunService.java`。
- `application.yml`、`pom.xml`、`.gitignore`。
- `dataAnalyze/**`、`sun/**`、`src/main/resources/static/frontend/**`。

---

## 十、测试计划

> 所有新测试基于 `@TempDir` 构造 `analysis-store/report-summaries/`,通过 `ReportSummaryStore` 直接喂入 fake summary,**不**依赖真实 `reports/`。

### 10.1 `HistoricalDiagnosticsServiceTest`

| # | 用例 | 期望 |
|---|---|---|
| H1 | corpus 空 | `corpus_size=0`,`matched_reports=0`,`matching_strategy="none"`,`checks` 含 `INSUFFICIENT_BASELINE`(无 current summary 还会含 `MISSING_SUMMARY`) |
| H2 | 当前 reportId 无 summary,corpus 有 5 份 | `checks` 含 `MISSING_SUMMARY`(error);仍输出 `basis.corpus_size=5`、`source_status_counts`;不做匹配/偏离 |
| H3 | scenario_id exact ≥ 5 | `matching_strategy="scenario_id_exact"`,`matched_reports=N`,产出 anomalies |
| H4 | scenario_id 缺失,fingerprint exact ≥ 5 | `matching_strategy="config_fingerprint"` |
| H5 | scenario_id/fingerprint 都不可用,similar_config ≥ 5 | `matching_strategy="similar_config"` |
| H6 | 全部不匹配 | `matching_strategy="none"`,`matched_reports=0`,`INSUFFICIENT_BASELINE` |
| H7 | matched=2 | 不产出 anomaly;`checks` 含 `INSUFFICIENT_BASELINE` |
| H8 | matched=4 | 产出 `historical_median`,无 robust_z,无 anomaly 项 |
| H9 | matched=8,某 metric MAD=0 | 该 metric 不进 anomaly;`warnings` 含 `MAD_ZERO:<metric>` |
| H10 | matched=8,`avg_wait_time_minutes` 偏离 |z|=4 | `anomalies[].metric=avg_wait_time_minutes`,`severity="warning"`,`robust_z` 输出 |
| H11 | source_status 混合(present 3、stale 2、missing 5) | 全部参与;`source_status_counts` 反映实际;`warnings` 含 `MISSING_SOURCE_NEIGHBORS`(missing 占比 > 50%) |
| H12 | parse_status=failed 的占位摘要 ≥ 1 | 不进 candidate;`excluded_counts.parse_failed >= 1` |
| H13 | basic_invariants_valid=false 的摘要 ≥ 1 | 不进 candidate;`excluded_counts.invariant_failed >= 1` |
| H14 | 当前 summary `basic_invariants_valid=false` | `checks` 含 `INVARIANT_FAILURE`(error,带 violations);仍做偏离 |
| H15 | metric 在当前 summary 中为 null | 该 metric 完全跳过;`warnings` 含 `METRIC_MISSING:<metric>` |
| H16 | 自身 reportId 在 corpus 内 | `excluded_counts.self == 1`,自身不进 baseline |
| H17 | 服务内部异常(mock store 抛 RuntimeException) | 输出 `DIAGNOSTICS_INTERNAL_ERROR` check;不抛上层 |
| H18 | 数值 NaN / Infinity | 不输出 NaN,改 null + 对应 warning |

### 10.2 `AnalysisControllerIntegrationTest`(增量)

| # | 用例 | 期望 |
|---|---|---|
| C1 | 不传 `include_historical_diagnostics` | 响应 JSON 顶层**无** `historical_diagnostics` 键(逐字与现有 baseline 对齐) |
| C2 | `include_historical_diagnostics=false` | 同 C1 |
| C3 | `include_historical_diagnostics=true`,corpus 空 | 顶层多 `historical_diagnostics`,`basis.corpus_size=0`,主 `data` 字段不少 |
| C4 | `include_historical_diagnostics=true`,corpus ≥ 5 | 顶层 `historical_diagnostics.basis.matched_reports >= 0`,`anomalies` 数组存在(可能为空) |
| C5 | C++ 不可用(503 路径)且启用诊断 | 503 body 含 `historical_diagnostics`,`available=false` 仍存在 |
| C6 | `/api/analysis/cross-scenario` 启用诊断标志(若客户端误传) | 响应**不**含 `historical_diagnostics`(本阶段批量接口不支持) |

### 10.3 全局回归

| # | 用例 | 期望 |
|---|---|---|
| G1 | `mvn -DskipFrontend=true test` | 全绿,数量 >= 阶段 1 验收的 183 + 新增 |
| G2 | `cd sun && npm run build:backend` | 通过 |
| G3 | `ServedFrontendBundleFreshnessTest` | 通过 |
| G4 | 不读完整 reports | 通过 grep 检查 `HistoricalDiagnosticsService` 源码:不出现 `Files.newInputStream` / `mapper.readTree(.*reports` / `Paths.get("reports"` 等模式 |
| G5 | 不调 C++ | grep:`HistoricalDiagnosticsService` 中无 `ProcessBuilder` / `Runtime.exec` / `dataAnalyze` |
| G6 | 不调 fallback | grep:`HistoricalDiagnosticsService` 不依赖 `InternalStatisticsAnalyzer` / `ExternalAnalysisService` |
| G7 | 默认契约不变 | `ScenarioRunServiceContractTest` / `AnalysisControllerIntegrationTest` 既有 case 零修改 |

---

## 十一、边界策略明确表

| 问题 | 策略 |
|---|---|
| missing summary 是否参与基线 | ✅ 参与;占比 > 50% 加 `MISSING_SOURCE_NEIGHBORS` |
| stale summary 是否参与基线 | ✅ 参与;若**全部**邻居 stale,加 `STALE_NEIGHBORS` |
| deleted summary 是否参与基线 | ✅ 参与(语义同 missing) |
| unverified summary 是否参与基线 | ✅ 参与(数据可用,只是源未核验过) |
| parse_status=failed 是否参与 | ❌ 永远排除,计入 `excluded_counts.parse_failed` |
| basic_invariants_valid=false 是否参与基线 | ❌ 永远排除,计入 `excluded_counts.invariant_failed` |
| 当前 reportId 在 corpus 中 | ❌ 自我排除,计入 `excluded_counts.self` |
| 当前 source_status=missing | ✅ 不影响诊断流程;加 `warnings: CURRENT_SOURCE_MISSING` |
| 当前 summary 缺 scenario_id | 跳过 Tier A,从 Tier B 开始 |
| 当前 summary 缺 config_fingerprint(`sha1:unavailable`) | 跳过 Tier B,从 Tier C 开始 |
| 当前 summary 关键字段全缺 | `matching_strategy="none"`,`SIMILAR_CONFIG_UNAVAILABLE` warning |
| 单个 metric 在 current 中 null | 跳过该 metric(`METRIC_MISSING:<metric>`),其它 metric 正常 |
| 单个 metric 在 neighbor 中 null | 该 neighbor 的该 metric 跳过,不影响其它 metric;`n` 是该 metric 的实际有效样本数 |
| MAD = 0 | 不输出 robust_z;不产生 anomaly 项;`warnings: MAD_ZERO:<metric>` |
| 数值 NaN / Infinity | 输出 null + warning,不抛 |
| `analysis-store/` 目录不存在 | `corpus_size=0`,`warnings: SUMMARY_STORE_UNAVAILABLE`,主分析不受影响 |
| `analysis-store/` 全是损坏 JSON | store 自动跳过,`corpus_size = 实际可解析数`,可能 = 0 |

---

## 十二、风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 摘要 schema 与本设计字段不一致 | 字段读不到,误判 missing | 实施前先 grep 所有 metric 在 `ReportSummaryExtractor` 中的输出键名,对齐到 `metrics.*`(已对齐 §六) |
| 当前响应 `data` 字段并非 `ObjectNode`(部分错误路径返回纯文本) | merge 失败 | merge 前 `instanceof ObjectNode` 检查;否则附加 warning,主响应不变 |
| 诊断耗时拖慢主请求 | p95 上升 | 算法 O(N)(N=corpus size,典型 < 1k);单次预期 < 50ms;若超 200ms 加 timeout 保护(本阶段先观测,不强制) |
| `list()` 在大 store 下 IO 开销 | 启动期慢 | 阶段 1 已 sorted by filename + corrupt skip;阶段 2 不二次 sort;不修改 store 接口 |
| 用户误以为 `severity=warning` 是质量分 | 体验偏差 | 字段名 `severity` 仅在 anomaly 内,文档明确"非综合评分";无 `level` / `score` 字段 |
| 滑动添加 metric 时回归现有 anomaly | 测试需更新 | metric 列表 §6.1 是封闭枚举;新增需另起 RFC |
| `includeHistoricalDiagnostics` 默认值被误置为 true | 旧客户端响应被改 | 类型 `Boolean`(对象,允许 null),默认 `null`;`null/false` 同义关闭;反序列化失败按关闭处理 |
| C++ 不可用时调用方依然期望诊断 | 503 body 缺字段 | §7.3:`available=false` 时仍合并诊断子树 |

---

## 十三、性能目标

| 操作 | 目标 |
|---|---|
| `diagnose(reportId)` corpus=100 | ≤ 30 ms p95 |
| `diagnose(reportId)` corpus=500 | ≤ 80 ms p95 |
| `diagnose(reportId)` corpus=1000 | ≤ 200 ms p95 |
| 启用诊断对 `/api/analysis/run` 主路径影响 | 增量 ≤ 100 ms p95(corpus ≤ 500) |
| 关闭诊断对 `/api/analysis/run` 主路径影响 | 0(代码路径不进入服务) |
| 堆增量 | ≤ 5 MB(摘要总和 ≤ 5 MB) |

---

## 十四、回滚方案

阶段 2 在主路径之外:
1. `AnalysisController` 中删除 1 行 `mergeHistoricalDiagnostics(...)`(或把 `includeHistoricalDiagnostics` 永远当 false 处理),响应即恢复阶段 1 形态。
2. `HistoricalDiagnosticsService.java` 与测试可整文件删除,无引用。
3. `analysis-store/` 不动,与阶段 1 解耦。

---

## 十五、验收标准

实施完成后视为阶段 2 完成,需同时满足:

1. ✅ 默认请求(无 `include_historical_diagnostics`)`/api/analysis/run` 响应**逐字段**与阶段 1 完成时一致;`AnalysisControllerIntegrationTest` 既有 case 无修改通过。
2. ✅ 启用诊断时,响应顶层多 `historical_diagnostics` 子树,字段集严格遵守 §七 schema,无 `quality_score` / `level` / `score` / `tier`。
3. ✅ `HistoricalDiagnosticsServiceTest` 全绿(H1–H18 ≥ 18 个 case)。
4. ✅ `mvn -DskipFrontend=true test` 全绿,总数 ≥ 183 + H/C 系列新增数。
5. ✅ `cd sun && npm run build:backend` 通过;`ServedFrontendBundleFreshnessTest` 通过。
6. ✅ `HistoricalDiagnosticsService.java` 中 grep 不出现:
   - `Paths.get("reports"`、`reports/`、`simulation-report-`、`simulation-history-`(除注释)
   - `ProcessBuilder`、`Runtime.exec`、`dataAnalyze`
   - `InternalStatisticsAnalyzer`、`ExternalAnalysisService`
7. ✅ 摘要 store 异常 / 内部异常时主分析路径不受影响(503 path 也满足),由 H17 + C5 守住。
8. ✅ C++ binary、前端 jsx 无任何改动,`AnalysisCore.cpp` / `源.cpp` / `sun/src/**` 文件 mtime 在 PR diff 中为 0。

---

## 十六、实施顺序建议(实施轮)

> 仅供未来实施轮参考,**本轮不动手**。

1. 写 `HistoricalDiagnosticsServiceTest`(TDD)—— 先固化 H1–H18 行为契约。
2. 实现 `HistoricalDiagnosticsService.diagnose(reportId)`,**永不抛**。
3. 扩展 `AnalysisController.RunRequest` + 在 `runForReport` 内 conditional merge。
4. 加 `AnalysisControllerIntegrationTest` 增量 case(C1–C6)。
5. 跑 `mvn -DskipFrontend=true test` + `npm run build:backend` + 验证 grep 6 项约束。
6. 自查:`/api/analysis/cross-scenario` 默认行为零变化(C6 守)。

---

## 十七、最终回答

1. **数据来源**:仅 `ReportSummaryStore.read/list`,绝不读 `reports/*.json` / 调 C++ / 调 fallback。
2. **匹配策略**:scenario_id_exact → config_fingerprint → similar_config(±10% box) → none,先取最高层 ≥3 邻居,否则取最高层任意邻居。
3. **指标偏离**:n≥5 用 median + MAD,3–4 只 median,<3 跳过;MAD=0 仅 warning。
4. **source_status 参与**:present/stale/missing/deleted/unverified 均参与;parse_failed / invariant_failed 排除;占比异常时 warnings 提示。
5. **JSON 输出**:`historical_diagnostics.{enabled, schema_version, computed_by, basis, checks, anomalies, warnings}`,**无** `quality_score` / `level`。
6. **启用方式**:`include_historical_diagnostics=true` 才输出;默认 `null/false` 时旧响应一字节不变。
7. **失败降级**:store 不可用 / 服务异常 / payload 非对象 → 主分析不受影响,诊断子树以 warning/check 表达失败。
8. **实现范围**:新建 `HistoricalDiagnosticsService` + 测试;修改 `AnalysisController.RunRequest` + merge 逻辑 + 1–2 条集成测试;不动 store / C++ / fallback / 前端 / yml。
9. **验收**:8 条硬约束全过(§十五)。

本 RFC 阶段:**只设计,不写代码**。等待用户批准后再进入实施轮。
