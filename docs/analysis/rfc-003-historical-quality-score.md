# RFC-003 阶段 3:Historical Quality Score(只设计,不写代码)

> 本轮交付:`docs/analysis/rfc-003-historical-quality-score.md`(本文)。
> **不创建/修改任何 `.java` / `.jsx` / `.yml` / `.xml` 文件,不写测试,不重构 phase 2。**

---

## 一、与 phase 1 / phase 2 的关系

### 1.1 phase 2 已经提供的事实

下面是 `HistoricalDiagnosticsService.diagnose(reportId)` 输出的字段清单(本 RFC 输入的"上游契约"),逐字摘自 RFC-002 §7.1 和 `HistoricalDiagnosticsService.java`:

| 字段路径 | 类型 | 用途 |
|---|---|---|
| `enabled` | bool | 始终 true(只在启用时构造) |
| `schema_version` | string | `"1.0"`,phase 3 需要把它写到 `historical_quality.basis.diagnostics_schema_version` |
| `computed_by` | string | `"java-summary-store"` |
| `basis.corpus_size` | int | reliability 维度输入 |
| `basis.usable_summaries` | int | reliability 维度输入 |
| `basis.matched_reports` | int | comparability 维度输入 |
| `basis.matching_strategy` | enum: `scenario_id_exact`/`config_fingerprint`/`similar_config`/`none` | comparability 维度输入 |
| `basis.current_summary_present` | bool | availability 维度 + score_available 闸门 |
| `basis.source_status_counts.{present,stale,missing,deleted,unverified}` | int | reliability 维度输入(占比) |
| `basis.excluded_counts.{parse_failed,invariant_failed,self}` | int | reliability 维度输入 |
| `basis.self_excluded` | bool | basis 透传,不影响打分 |
| `basis.policy.*` | object | basis 透传,不影响打分 |
| `checks[].code` | string | `MISSING_SUMMARY`/`CURRENT_PARSE_FAILED`/`INVARIANT_FAILURE`/`INSUFFICIENT_BASELINE`/`DIAGNOSTICS_INTERNAL_ERROR` |
| `checks[].severity` | enum: `info`/`warning`/`error` | 决定扣分系数 |
| `checks[].message`/`context` | string/object | 透传到 `historical_quality.warnings`(脱敏) |
| `anomalies[].metric` | string | stability 维度,逐 metric 扣分 |
| `anomalies[].robust_z` | number | stability 维度,信号强度 |
| `anomalies[].severity` | enum: `info`/`warning` | 决定扣分系数 |
| `anomalies[].historical_median` / `mad` / `n` / `current` | number | 透传至 `penalties[].source` 描述,不二次计算 |
| `warnings[]` | string[] | `METRIC_MISSING:*` / `MAD_ZERO:*` / `MISSING_SOURCE_NEIGHBORS` / `STALE_NEIGHBORS` / `CURRENT_SOURCE_MISSING` / `SIMILAR_CONFIG_UNAVAILABLE` |

### 1.2 phase 3 可直接消费、不重算的字段

- **匹配结果**:`matching_strategy + matched_reports`。**phase 3 永不**重新做 scenario_id / fingerprint / similar_config 匹配。
- **指标偏离**:`anomalies[].robust_z + severity`。**phase 3 永不**重算 median / MAD,不再访问摘要的 `metrics.*`。
- **不变量结果**:`checks[INVARIANT_FAILURE].context.violations`。**phase 3 永不**重检不变量。
- **样本健康度**:`source_status_counts` + `excluded_counts`。**phase 3 永不**遍历 store 计数。
- **诊断成败信号**:`checks[DIAGNOSTICS_INTERNAL_ERROR]`。**phase 3 永不**自己尝试 `store.list()`/`store.read()` 来"补诊断"。

### 1.3 phase 3 不应该重复的内容(硬约束)

- ❌ 不调 `ReportSummaryStore.list()` / `read()` —— phase 3 的输入是 `historical_diagnostics` `ObjectNode`,不是 store。
- ❌ 不读 `reports/*.json`、`simulation-history-*.json`、`simulation-report-latest.json`。
- ❌ 不调 C++ binary、`InternalStatisticsAnalyzer`、`ExternalAnalysisService`。
- ❌ 不再算 robust z、MAD、median、fingerprint、scenario 匹配。
- ❌ 不在 phase 3 引入新的统计学(bootstrap / 卡方 / Bayes / ML)。
- ❌ 不重新扫描或聚合摘要文件。

### 1.4 phase 3 是否应该调用 HistoricalDiagnosticsService

**应该**,但 phase 3 只是**消费**它。不重新实现匹配/MAD。

调用方关系两种候选:

| 方案 | 由谁调 diagnose | 优点 | 缺点 |
|---|---|---|---|
| (A) controller 调 diagnose,把结果传给 scorer | controller | 单一调用点;diagnostics 与 quality 都从同一份 ObjectNode 派生,**保证 basis/checks/anomalies/warnings 一致** | controller 多 1 行注入 |
| (B) scorer 自己持有 `HistoricalDiagnosticsService` 并内部 diagnose | scorer | controller 更简单 | scorer 与 diagnose 双调用容易出现两次计算,语义漂移风险 |

**推荐 (A)**。phase 3 保证"同一次诊断结果同时产 diagnostics 输出和 quality 输出",basis 字段逐字同源,杜绝两边数字不一致。

### 1.5 是否需要必读 `ReportSummaryStore`?

**不需要**。phase 3 不直接读 store。如果未来出现"诊断未启用但还想算 quality"的场景,phase 3 也只通过调用 `HistoricalDiagnosticsService.diagnose(reportId)` 间接拿数据,而不是直接 `store.list()`。

---

## 二、quality_score 的语义

### 2.1 它**不是**什么

- **不是**"食堂运行得好不好"的评分。
- **不是**配置参数优劣的评分。
- **不是**业务建议指标。
- **不是**排序依据。
- **不是**KPI。

### 2.2 它**是**什么

> **`quality_score` = 当前 reportId 的"诊断可信度评分":
> 在 phase 2 已经给出的事实基础上,综合 数据可用性 / 历史可比性 / 指标稳定性 / 数据可信度 4 个维度,
> 给出"当前这份诊断结论本身有多大概率反映了真实情况"的保守估计。**

### 2.3 五维度评估(用户列出的范围)

phase 3 第一版采用 **4 维输出 + 1 维内化为闸门**:

| 维度 | 是否进入 v1 输出 | 处理方式 |
|---|---|---|
| 1. Data Availability | ✅ `dimensions.availability` | 维度分 |
| 2. Historical Comparability | ✅ `dimensions.comparability` | 维度分 |
| 3. Metric Stability | ✅ `dimensions.stability` | 维度分 |
| 4. Diagnostic Reliability | ✅ `dimensions.reliability` | 维度分 |
| 5. Business Safety | ⚠ **不**单独输出 | 内化为 `score_available` 闸门 + INVARIANT_FAILURE 强降级 |

**理由**:Business Safety 与"业务质量"语义最接近,把它做成独立分数最容易让用户误读为"这家食堂安全不安全"。改为闸门:
- `INVARIANT_FAILURE`(error) → 触发 level 至少为 `unreliable`,且 availability 维度大幅扣分。
- `MISSING_SUMMARY` / `CURRENT_PARSE_FAILED` / `DIAGNOSTICS_INTERNAL_ERROR` → 直接 `score_available=false`。

### 2.4 维度打分规则(每个维度独立 0–1)

每个维度起始 1.0,触发对应规则后**逐项扣减**,夹紧到 [0, 1]。`reasons` 列表逐条记录,前端可直接展示。

---

#### 2.4.1 Availability(数据可用性)

| 触发条件 | 扣分 | reasons 文本 |
|---|---|---|
| `current_summary_present == false` | → score_available=false(不进入 dim 计算) | — |
| `checks[].code == "CURRENT_PARSE_FAILED"` | → score_available=false | — |
| `checks[].code == "INVARIANT_FAILURE"`(error) | -0.50 | `current_invariant_failure:<count>` |
| `warnings[]` 含 `CURRENT_SOURCE_MISSING` | -0.05 | `current_source_missing` |
| `METRIC_MISSING:*` 出现 K 个 | -0.03 × K,封顶 -0.15 | `metric_missing:K` |

#### 2.4.2 Comparability(历史可比性)

| 触发条件 | 扣分 | reasons 文本 |
|---|---|---|
| `matching_strategy == "none"` | -0.60 | `strategy_none` |
| `matching_strategy == "similar_config"` | -0.20 | `strategy_similar_only` |
| `matching_strategy == "config_fingerprint"` | -0.05 | `strategy_fingerprint` |
| `matching_strategy == "scenario_id_exact"` | 0 | — |
| `matched_reports < 3` | -0.40 | `matched_reports=<n>` |
| `3 <= matched_reports < 5` | -0.20 | `matched_reports=<n>` |
| `5 <= matched_reports < 10` | -0.05 | `matched_reports=<n>` |
| `matched_reports >= 10` | 0 | — |
| `warnings[]` 含 `SIMILAR_CONFIG_UNAVAILABLE` | -0.05 | `similar_config_unavailable` |

> 同一份 diagnostics 的扣分**叠加但封顶 1.0**,因此最差为 0。

#### 2.4.3 Stability(指标稳定性)

| 触发条件 | 扣分 | reasons 文本 |
|---|---|---|
| 每条 `anomaly.severity == "warning"` | -0.15 | `warning_anomaly:<metric>` |
| 每条 `anomaly.severity == "info"` | -0.05 | `info_anomaly:<metric>` |
| 每个 `MAD_ZERO:*` warning | -0.02,封顶 -0.10 | `mad_zero:<n>` |
| `matched_reports < 3`(stability 不能算偏离) | -0.30 + `reasons` 加 `stability_skipped` | `stability_skipped` |

注意:`MAD_ZERO` 是"邻居完全一致 → 没法判异常",不构成异常证据,因此扣得很轻。

#### 2.4.4 Reliability(数据可信度)

| 触发条件 | 扣分 | reasons 文本 |
|---|---|---|
| `corpus_size == 0` | -0.50 | `corpus_empty` |
| `0 < corpus_size < 5` | -0.20 | `corpus_small:<n>` |
| `usable_summaries / max(corpus_size, 1) < 0.7` | -0.20 | `low_usable_ratio:<r>` |
| `(missing+deleted+unverified)/matched_reports > 0.5` | -0.15 | `weak_source_status_neighbors:<r>` |
| `warnings[]` 含 `STALE_NEIGHBORS`(全部邻居 stale) | -0.10 | `stale_neighbors` |
| `warnings[]` 含 `MISSING_SOURCE_NEIGHBORS` | -0.05 | `missing_source_neighbors` |
| `excluded_counts.parse_failed > 0` | -0.02 × min(count, 5) | `parse_failed_in_corpus:<n>` |

> Reliability 反映的是"邻居池整体是否健康",**不**与当前报告的内容强相关。

### 2.5 综合分计算(保守取最小)

```
quality_score = round2( min(availability, comparability, stability, reliability) )
quality_score_percent = round( quality_score * 100 )  // 整数
```

**用 `min` 而非加权平均的理由**:

- 加权平均会让"基线只有 2 条"被"语料 1000 条"中和,产生看似可信的中分。
- `min` 等价于"当前诊断的可信度等于其最弱维度"。这个语义清楚、可解释、保守,与 RFC §2.2 的语义吻合。
- 用户列出的方案 B(加权制)在第一版被显式否决,与 §四 推荐一致。

### 2.6 哪些情况只 warning 不扣分

- `MAD_ZERO:*` 占绝大多数 metric:已在 stability 中按封顶处理,不再额外扣分。
- `excluded_counts.invariant_failed > 0` (语料中其他报告失败):**不扣 reliability**,只在 `warnings[]` 加 `BASELINE_HAS_INVARIANT_FAILURES:<n>` 提示;因为 phase 2 已经把它们排除出 baseline,对当前评分无影响。
- `basis.policy.*`:不参与扣分。

### 2.7 哪些情况让 score 直接不可用(score_available=false)

见 §三。

---

## 三、`score_available` 规则

### 3.1 score_available=false 的触发

| 条件 | unavailable_reason | level |
|---|---|---|
| `historical_diagnostics == null`(传入空) | `DIAGNOSTICS_NOT_PROVIDED` | `unavailable` |
| `historical_diagnostics` 不是 ObjectNode | `DIAGNOSTICS_NOT_OBJECT` | `unavailable` |
| `checks[]` 含 `MISSING_SUMMARY` | `MISSING_SUMMARY` | `unavailable` |
| `checks[]` 含 `CURRENT_PARSE_FAILED` | `CURRENT_PARSE_FAILED` | `unavailable` |
| `checks[]` 含 `DIAGNOSTICS_INTERNAL_ERROR` | `DIAGNOSTICS_INTERNAL_ERROR` | `unavailable` |
| `corpus_size == 0` 且 `current_summary_present == false` | `EMPTY_CORPUS_AND_NO_CURRENT` | `unavailable` |
| `matched_reports == 0` 且当前缺 `metrics` 关键字段 ≥ 3 项 | `INSUFFICIENT_LOCAL_AND_GLOBAL` | `unavailable` |

> **score_available 是闸门**:闸门关闭时**不**输出 `quality_score` / `quality_score_percent` / `dimensions` / `penalties`。
> level 强制 `unavailable`,这是约定俗成"无法判断"的表达,而非低分。

### 3.2 score_available=true 时的 level 规则

按以下优先级**自上而下**判断,先匹配的为准:

```
if any check.severity == "error" AND check.code in {INVARIANT_FAILURE}:
    level = "unreliable"   (即便 score 高,error 级核心 check 仍降级)
elif quality_score < 0.40:
    level = "unreliable"
elif quality_score < 0.70 OR (any anomaly.severity == "warning"):
    level = "caution"
elif quality_score < 0.85:
    level = "usable"
else:
    level = "reliable"
```

注意:**有任意 warning 级 anomaly 时,level 上限即 `caution`**,即便 score >= 0.85。这是为了避免"两个偏离 anomaly 但加权后还得 0.88"的迷惑场景。

### 3.3 不输出"伪精确分数"

`score_available=false` 时:
- **不**输出 `quality_score`(连 0 都不输出)
- **不**输出 `quality_score_percent`
- **不**输出 `dimensions`
- **不**输出 `penalties`
- 输出 `level: "unavailable"` + `unavailable_reason`

---

## 四、Scoring Model 三方案对比

| 维度 | A 扣分制 | B 加权维度 | C 等级规则优先 |
|---|---|---|---|
| 可解释性 | 强 —— 每条扣分都能溯源到 diagnostics 一个字段 | 中 —— 权重选择主观 | 强 —— 但只输出等级,不输出连续分 |
| 测试难度 | 低 —— 每条规则一条单测 | 中 —— 需对各维度权重校准 | 低 —— 规则枚举 |
| 用户误解风险 | 低 —— "扣了 0.15 因为出现 1 条 warning anomaly" | 高 —— "0.78" 这种数字看起来是综合质量 | 中 —— 等级简洁但缺少颗粒 |
| 后续可扩展性 | 高 —— 加新规则不破坏旧分数 | 中 —— 新维度要重平衡权重 | 低 —— 需重设规则边界 |
| 与 phase 2 字段贴合 | 强 —— 直接映射 checks/anomalies/warnings | 弱 —— 需把 checks 抽象成连续值 | 中 |
| 适合第一版 | ✅ | ⚠ 复杂、易误读 | ⚠ 仅给 level 但失去细节 |

### 4.1 推荐方案

**A(扣分制) + C(规则前置闸门) 混合**。

- 规则闸门(C)先决定 `score_available`(MISSING_SUMMARY 等硬条件直接 unavailable)。
- 维度扣分(A)在每个维度独立从 1.0 起扣,扣完夹紧到 [0,1]。
- 综合分 = `min(dimension scores)`(保守)。
- level 由综合分 + error check 决定(C 风格)。

**为什么不用 B**:用户明确"第一版宁可保守,不要输出看似精确但依据不足的分数",B 的权重选择没有第一性原则可依靠,会给出"看起来精确但其实拍脑袋"的数字。

---

## 五、level 设计

### 5.1 等级定义(phase 3 第一版)

| level | 语义(单句) | 触发 |
|---|---|---|
| `reliable` | 当前诊断的可信度足够支持深入分析 | score >= 0.85 且无任何 warning anomaly 且无 error check |
| `usable` | 当前诊断可作为粗略参考 | 0.70 <= score < 0.85 且无 warning anomaly 且无 error check |
| `caution` | 当前诊断包含显著告警,需谨慎使用 | 0.40 <= score < 0.70 或 存在 ≥1 条 warning anomaly |
| `unreliable` | 当前诊断不应直接采纳 | score < 0.40 或 存在 INVARIANT_FAILURE error check |
| `unavailable` | 数据不足以给出诊断 | score_available=false |

### 5.2 关键问题解答

1. **level 是否必须输出?** ✅ 始终必须输出。`score_available=false` 时输出 `unavailable`。
2. **level 与 quality_score 的关系?** level 由 quality_score + error check 派生,但**level 始终输出**,而 score 只在 `score_available=true` 时输出。
3. **level 是否更适合前端展示?** 是。前端建议**只展示 level + reasons 摘要**,默认不展示数字 score。把 `quality_score` / `dimensions` 留给开发/分析视图。
4. **什么情况只输出 level 不输出 score?** `score_available=false`,即 §3.1 所有条件之一。
5. **如何避免误解为业务优劣?** §六 schema 中包含 `warnings: ["QUALITY_SCORE_IS_DIAGNOSTIC_ONLY", "NOT_A_BUSINESS_PERFORMANCE_SCORE"]`,这两条 warning 在第一版**始终输出**;同时文档化"这是数据质量与历史可比性评分"。

---

## 六、JSON Schema

### 6.1 完整结构(score_available=true 时)

```json
{
  "historical_quality": {
    "enabled": true,
    "schema_version": "1.0",
    "computed_by": "java-quality-scorer",
    "computed_at_epoch_millis": 1747641600000,

    "score_available": true,
    "quality_score": 0.82,
    "quality_score_percent": 82,
    "level": "usable",

    "basis": {
      "diagnostics_used": true,
      "diagnostics_schema_version": "1.0",
      "current_report_id": "abc123",
      "current_summary_present": true,
      "matched_reports": 8,
      "matching_strategy": "scenario_id_exact",
      "corpus_size": 120,
      "usable_summaries": 113,
      "anomaly_count": {
        "warning": 1,
        "info": 0
      }
    },

    "dimensions": {
      "availability": {
        "score": 1.0,
        "reasons": []
      },
      "comparability": {
        "score": 0.95,
        "reasons": ["matched_reports=8"]
      },
      "stability": {
        "score": 0.85,
        "reasons": ["warning_anomaly:avg_wait_time_minutes"]
      },
      "reliability": {
        "score": 0.82,
        "reasons": ["weak_source_status_neighbors:0.62"]
      }
    },

    "penalties": [
      {
        "code": "WARNING_ANOMALY",
        "dimension": "stability",
        "amount": 0.15,
        "source": "historical_diagnostics.anomalies[0]:avg_wait_time_minutes"
      },
      {
        "code": "MATCHED_REPORTS_RANGE",
        "dimension": "comparability",
        "amount": 0.05,
        "source": "historical_diagnostics.basis.matched_reports=8"
      },
      {
        "code": "WEAK_SOURCE_STATUS_NEIGHBORS",
        "dimension": "reliability",
        "amount": 0.18,
        "source": "historical_diagnostics.basis.source_status_counts"
      }
    ],

    "warnings": [
      "QUALITY_SCORE_IS_DIAGNOSTIC_ONLY",
      "NOT_A_BUSINESS_PERFORMANCE_SCORE"
    ]
  }
}
```

### 6.2 score_available=false 时

```json
{
  "historical_quality": {
    "enabled": true,
    "schema_version": "1.0",
    "computed_by": "java-quality-scorer",
    "computed_at_epoch_millis": 1747641600000,

    "score_available": false,
    "level": "unavailable",
    "unavailable_reason": "MISSING_SUMMARY",

    "basis": {
      "diagnostics_used": true,
      "diagnostics_schema_version": "1.0",
      "current_report_id": "abc123",
      "current_summary_present": false,
      "matched_reports": 0,
      "matching_strategy": "none",
      "corpus_size": 0,
      "usable_summaries": 0
    },

    "warnings": [
      "QUALITY_SCORE_IS_DIAGNOSTIC_ONLY",
      "NOT_A_BUSINESS_PERFORMANCE_SCORE"
    ]
  }
}
```

### 6.3 字段表

| 字段 | 必出 | score_available=false 时是否出 | 说明 |
|---|---|---|---|
| `enabled` | ✅ 始终 | ✅ | 固定 true(只在启用时构造) |
| `schema_version` | ✅ | ✅ | 固定 `"1.0"` |
| `computed_by` | ✅ | ✅ | 固定 `"java-quality-scorer"` |
| `computed_at_epoch_millis` | ✅ | ✅ | 系统时间 |
| `score_available` | ✅ | ✅ | bool |
| `quality_score` | ✅ 仅 true | ❌ 不出 | `[0,1]`,2 位小数 |
| `quality_score_percent` | ✅ 仅 true | ❌ 不出 | 整数 `[0,100]`,= round(quality_score × 100) |
| `level` | ✅ 始终 | ✅ | enum: reliable/usable/caution/unreliable/unavailable |
| `unavailable_reason` | ❌ | ✅ 必出 | enum,见 §3.1 |
| `basis.diagnostics_used` | ✅ | ✅ | bool;第一版恒为 true |
| `basis.diagnostics_schema_version` | ✅ | ✅ | 透传 phase 2 schema_version |
| `basis.current_report_id` | ✅ | ✅ | 字符串 |
| `basis.current_summary_present` | ✅ | ✅ | 透传 |
| `basis.matched_reports` | ✅ | ✅ | 透传 |
| `basis.matching_strategy` | ✅ | ✅ | 透传 |
| `basis.corpus_size` | ✅ | ✅ | 透传 |
| `basis.usable_summaries` | ✅ | ✅ | 透传 |
| `basis.anomaly_count.{warning,info}` | ✅ 仅 true | ❌ | 计数 |
| `dimensions.{availability,comparability,stability,reliability}.score` | ✅ 仅 true | ❌ | `[0,1]`,2 位小数 |
| `dimensions.*.reasons[]` | ✅ 仅 true | ❌ | string[] |
| `penalties[]` | ✅ 仅 true(可空数组) | ❌ | 见 §6.1 |
| `warnings[]` | ✅ 始终(至少含 2 条免责声明) | ✅ | string[] |

### 6.4 schema_version 演进

- v1.0:本 RFC 字段集。
- 后续小调整(加字段、收紧范围)→ v1.x。
- 破坏性变更(改字段语义、删字段)→ v2.0,与 phase 2 schema_version 解耦。
- `historical_quality.schema_version` 与 `historical_diagnostics.schema_version` **独立**演进,前者必透传后者作为 `basis.diagnostics_schema_version`。

### 6.5 phase 3 不修改 historical_diagnostics 的 schema

**重要**:phase 3 是 **diagnostics 的消费者**,不再扩展 phase 2 的 schema。如果 phase 3 实施过程中发现 diagnostics 缺字段,**回炉 phase 2 RFC 修订**,而不是在 phase 3 偷偷扩展。

---

## 七、选择性启动

### 7.1 请求参数

扩展 `AnalysisController.RunRequest`:

```java
@JsonAlias({"include_historical_quality", "includeHistoricalQuality"})
private Boolean includeHistoricalQuality;
```

- 默认 `null`,语义同 `false`。
- 仅 `true` 时调用 `HistoricalQualityScorer`。
- 类型错误 → 沿用 Spring/Jackson 默认行为(可能 400),不刻意吞错(对齐 phase 2 修正 2)。

### 7.2 与 `include_historical_diagnostics` 的关系

#### 7.2.1 两方案对比

| 维度 | A 仅输出 quality | B 自动同时输出 diagnostics |
|---|---|---|
| 响应体大小 | 小,quality 子树 ~1–2KB | 较大,quality + diagnostics 共 3–5KB |
| 用户可解释性 | quality.basis 里有诊断引用,但前端拿不到细节 | 两者并列,前端拼图直观 |
| 前端实现难度 | 简单(只渲染 quality) | 中(需同时处理两个子树) |
| 调试难度 | 较高(出问题需手动加 diagnostics flag 复现) | 低 |
| 契约清晰度 | 高 —— 一个 flag 控制一个子树 | 低 —— 启用 quality 自动启用 diagnostics 的副作用 |

#### 7.2.2 推荐方案 A

理由(与用户倾向一致):

- **契约清晰**:一个 flag 控制一个子树,不出现"启用 X 自动启用 Y"的隐式副作用。
- **响应最小化**:不需要 diagnostics 细节的调用方拿不到无用 ~3KB JSON。
- **可调试**:出问题时调用方显式同时启用两个 flag(`include_historical_diagnostics=true` + `include_historical_quality=true`),立即拿到全量数据复现问题。这是显式而非隐式的。

但作为代价,phase 3 在 `historical_quality.basis` 中**必出** `diagnostics_used: true` 字段,这样调用方至少知道"quality 是基于 diagnostics 算的"。

#### 7.2.3 四种 flag 组合行为

| `include_historical_diagnostics` | `include_historical_quality` | 响应顶层 |
|---|---|---|
| 缺省/false | 缺省/false | 都不出(同 phase 2 完成态) |
| true | 缺省/false | 只出 `historical_diagnostics`(同 phase 2) |
| 缺省/false | true | **只出 `historical_quality`**;controller 内部仍会调 `diagnose()` 喂给 scorer,但**不**把 diagnostics 子树合到响应 |
| true | true | 同时出 `historical_diagnostics` + `historical_quality`,且两者基于同一次 `diagnose()` 调用 |

> **重要保证**:第 4 种情况下,controller **只调一次** `diagnose(reportId)`,把同一个 ObjectNode 既合并到响应又喂给 scorer。这是 §1.4 推荐方案 (A) 的直接体现。

### 7.3 失败降级

`HistoricalQualityScorer.score(diagnosticsNode)` 必须**永不抛**(对齐 phase 2 的 `HistoricalDiagnosticsService.diagnose`)。任何内部异常 → 输出:

```json
{
  "historical_quality": {
    "enabled": true,
    "schema_version": "1.0",
    "computed_by": "java-quality-scorer",
    "score_available": false,
    "level": "unavailable",
    "unavailable_reason": "QUALITY_SCORER_INTERNAL_ERROR",
    "basis": { "diagnostics_used": true },
    "warnings": ["QUALITY_SCORE_IS_DIAGNOSTIC_ONLY", "NOT_A_BUSINESS_PERFORMANCE_SCORE"]
  }
}
```

主分析路径不受影响(对齐 phase 1 / phase 2 的非阻塞约束)。

### 7.4 cross-scenario 接口

`/api/analysis/cross-scenario` **不**支持 `include_historical_quality`。误传时与 phase 2 一样静默忽略,不报错。理由:cross-scenario 的输入是多个 reportId,不存在"当前报告"语义;quality 是 per-report 的诊断分。

---

## 八、实现范围(实施时,不在本轮)

### 8.1 输入类型决策

phase 3 第一版,`HistoricalQualityScorer.score(...)` 直接接收 `ObjectNode diagnostics` 而**非** Java DTO。

#### 8.1.1 理由

- **避免重构 phase 2**:`HistoricalDiagnosticsService.diagnose` 当前返回 `ObjectNode`。要改 DTO 化必须改 phase 2 签名 + 改 controller merge + 改 phase 2 测试。这等于回炉 phase 2,违反"不要重构已有服务"。
- **测试方便**:scorer 单测可任意构造 `ObjectNode`,无需建一堆 DTO 桩。
- **未来可平滑升级**:当 schema 稳定后再抽 DTO,phase 3 切换只是改输入类型,内部逻辑不动。

#### 8.1.2 三个候选问题的回答

| 问题 | 答 |
|---|---|
| Q1: scorer 接 ObjectNode 还是 DTO? | **ObjectNode**。 |
| Q2: phase 2 是否需要先抽 DTO? | **不需要**。phase 3 第一版直接读 ObjectNode 的字段。 |
| Q3: 抽 DTO 是否会大范围重构 phase 2? | 会。`diagnose()` 签名要变,所有 H1–H18 测试要跟着变。第一版**显式拒绝**这条路径。 |
| Q4: 第一版能否直接解析 ObjectNode? | 能。Jackson `path()` API 在 phase 2 已重度使用,phase 3 同款写法即可。 |

### 8.2 文件清单

#### 新增

| 文件 | 职责 |
|---|---|
| `service/HistoricalQualityScorer.java` | Spring `@Service`;`score(ObjectNode diagnostics, String reportId)` 永不抛;返回 `ObjectNode` |
| `service/HistoricalQualityScorer.java` 内部 record/类 | `Penalty`、`DimensionResult` 等小型不可变结构(单文件内部) |
| `test/.../service/HistoricalQualityScorerTest.java` | Q1–Q24 单测,直接构造 ObjectNode diagnostics |

#### 修改

| 文件 | 改动 |
|---|---|
| `controller/AnalysisController.java` | `RunRequest` 加 `Boolean includeHistoricalQuality`;`runForReport` 内决定调一次 `diagnose`,按 §7.2.3 的四种组合分发;新增私有方法 `maybeMergeQuality(payload, diagnostics, reportId)` |
| `controller/AnalysisControllerIntegrationTest.java` | 新增 8–10 个集成 case 覆盖四种 flag 组合 + 503 路径 + cross-scenario 不支持 |

#### 不修改(硬约束)

- `service/HistoricalDiagnosticsService.java`(phase 2 入口签名 + 行为完全冻结)
- `service/ReportSummaryStore.java` / `service/ReportSummaryExtractor.java`
- `service/SimulationReportRepository.java` / `service/InternalStatisticsAnalyzer.java` / `service/ExternalAnalysisService.java`
- `dataAnalyze/**`、`sun/src/**`、`src/main/resources/static/frontend/**`
- `application.yml`、`pom.xml`、`.gitignore`

### 8.3 调用拓扑

```
controller.runForReport(req)
  ├── externalAnalysisService.runForReport(reportId)        # phase 0
  │
  ├── 如果 (includeDiagnostics || includeQuality):
  │     diagnostics = historicalDiagnosticsService.diagnose(reportId)   # phase 2,只调一次
  │
  ├── 如果 includeDiagnostics:  payload.set("historical_diagnostics", diagnostics)
  ├── 如果 includeQuality:      payload.set("historical_quality",
  │                                          historicalQualityScorer.score(diagnostics, reportId))
  └── return wrap(...)
```

**关键**:`diagnose` 在两个 flag 同时为 true 时**只调一次**,保证 diagnostics 子树和 quality.basis 来源同一份 ObjectNode。

---

## 九、测试计划(实施时,不在本轮)

> 所有新测试基于 ObjectNode 构造的"diagnostics 桩",**不**需要 `@TempDir`、不需要 `ReportSummaryStore`、不依赖真实 controller 启动。

### 9.1 `HistoricalQualityScorerTest`(单元)

| # | 用例 | 期望 |
|---|---|---|
| Q1 | diagnostics=null | score_available=false,reason=DIAGNOSTICS_NOT_PROVIDED |
| Q2 | diagnostics 是 ArrayNode 等非 ObjectNode | score_available=false,reason=DIAGNOSTICS_NOT_OBJECT |
| Q3 | checks 含 MISSING_SUMMARY | score_available=false,reason=MISSING_SUMMARY,level=unavailable |
| Q4 | checks 含 CURRENT_PARSE_FAILED | score_available=false,reason=CURRENT_PARSE_FAILED |
| Q5 | checks 含 DIAGNOSTICS_INTERNAL_ERROR | score_available=false,reason=DIAGNOSTICS_INTERNAL_ERROR |
| Q6 | corpus_size=0 且 current_summary_present=false | score_available=false,reason=EMPTY_CORPUS_AND_NO_CURRENT |
| Q7 | matching_strategy=scenario_id_exact, matched=10, anomalies=[], 无 warning | score>=0.85,level=reliable |
| Q8 | matched=8, 1 条 warning anomaly | level=caution(warning anomaly 上限封 caution) |
| Q9 | matched=2 | comparability < 0.6,stability_skipped reason 出现 |
| Q10 | INVARIANT_FAILURE check 存在 | level=unreliable(无视 score) |
| Q11 | 4 条 METRIC_MISSING:* | availability 扣 0.12 (-0.03 × 4) |
| Q12 | 6 条 METRIC_MISSING:* | availability 扣 0.15 (封顶) |
| Q13 | matching_strategy=none | comparability 扣 0.60+ |
| Q14 | matching_strategy=similar_config | comparability 扣 0.20 |
| Q15 | source_status_counts: missing=10/present=2 in matched | reliability 扣 weak_source_status_neighbors |
| Q16 | warnings 含 STALE_NEIGHBORS | reliability 扣 0.10 |
| Q17 | warnings 含 MISSING_SOURCE_NEIGHBORS | reliability 扣 0.05 |
| Q18 | 多条 MAD_ZERO:* | stability 扣 ≤ 0.10(封顶) |
| Q19 | 6 条 anomalies(2 warning + 4 info) | stability 扣 0.30+0.20=0.50,夹紧到 [0,1] |
| Q20 | 全字段递归扫描:不出现 score 之外的 quality_score 重复键、不出现 phase 2 字段 | 通过 |
| Q21 | quality_score 范围 ∈ [0,1],quality_score_percent ∈ [0,100] | 范围断言 |
| Q22 | quality_score 不出 NaN/Infinity | 任意输入下断言 |
| Q23 | scorer 内部抛 RuntimeException(用反射注入坏字段)→ 永不抛 | 输出 reason=QUALITY_SCORER_INTERNAL_ERROR |
| Q24 | dimensions 总是 4 项,reasons 是数组(可空) | 结构稳定 |
| Q25 | warnings[] 始终含 QUALITY_SCORE_IS_DIAGNOSTIC_ONLY + NOT_A_BUSINESS_PERFORMANCE_SCORE | 免责声明守约束 |

### 9.2 `AnalysisControllerIntegrationTest`(增量)

| # | 用例 | 期望 |
|---|---|---|
| C7 | 无任何 include flag | 响应**不含** `historical_quality` 也**不含** `historical_diagnostics` |
| C8 | `include_historical_quality=false` | 同 C7 |
| C9 | `include_historical_quality=true`(单独) | 顶层有 `historical_quality`,**无** `historical_diagnostics`;`basis.diagnostics_used=true` |
| C10 | `include_historical_quality=true` + `include_historical_diagnostics=true` | 同时有 `historical_quality` + `historical_diagnostics`;两者 basis 字段一致(matched_reports 等数字逐字相同) |
| C11 | `include_historical_quality=true`,503 路径(missing-id) | 响应是 503,body.data 含 `historical_quality.score_available=false`,`unavailable_reason=MISSING_SUMMARY`,`level=unavailable` |
| C12 | `include_historical_quality=true`,递归断言不出现 quality_score / level / tier / score 之外的 forbidden 字段(对应 phase 2 的 schema 守) | 通过 |
| C13 | camelCase 别名 `includeHistoricalQuality=true` | 同 C9 |
| C14 | cross-scenario 接口误传 `include_historical_quality=true` | 响应**不含** `historical_quality` |
| C15 | 同一次请求两个 flag 都 true 时,验证 controller 只调一次 diagnose(可通过 spy 或日志计数实现) | 1 次 |

### 9.3 全局回归

| # | 用例 | 期望 |
|---|---|---|
| G1 | `mvn -DskipFrontend=true test` | 全绿,数量 ≥ 207 + 新增 |
| G2 | `cd sun && npm run build:backend` | 通过 |
| G3 | `ServedFrontendBundleFreshnessTest` | 通过 |
| G4 | grep `HistoricalQualityScorer.java` 不出现:`reports/`、`simulation-report-`、`simulation-history-`、`ProcessBuilder`、`Runtime.exec`、`dataAnalyze`、`InternalStatisticsAnalyzer`、`ExternalAnalysisService`、`ReportSummaryStore` | 通过 |
| G5 | phase 2 既有 24 个 case(H1–H18 + schema 守 + C1–C6)零修改通过 | 通过 |
| G6 | phase 1 既有 ReportSummaryStore + Repository 测试零修改通过 | 通过 |

---

## 十、风险与缓解

### 10.1 主要风险

| # | 风险 | 严重性 | 缓解 |
|---|---|---|---|
| R1 | 用户把 `quality_score=0.62` 误读为"食堂运行差" | **高** | (a) 始终输出 `warnings: ["QUALITY_SCORE_IS_DIAGNOSTIC_ONLY", "NOT_A_BUSINESS_PERFORMANCE_SCORE"]`;(b) 文档明确"数据质量 ≠ 业务质量";(c) 前端建议默认只展示 level,score 留给开发视图;(d) `level` 标签全是中性词,无 excellent/bad |
| R2 | 历史样本不足时仍输出分数 → 误导 | 高 | `matched_reports < 3` 时 stability 维度直接扣 0.30 + reasons 含 `stability_skipped`;`matched_reports == 0` 且当前缺字段时直接 `score_available=false` |
| R3 | 不同 scenario 错误归并,分数失真 | 中 | phase 2 已通过 scenario_id_exact / fingerprint / similar_config 三层匹配解决;`matching_strategy != "scenario_id_exact"` 在 comparability 维度有梯度扣分,弱匹配自然得分低 |
| R4 | source_status=missing/deleted 邻居拉低可信度 | 中 | reliability 维度对 `(missing+deleted+unverified)/matched > 0.5` 显式扣分;phase 2 的 `MISSING_SOURCE_NEIGHBORS` warning 直接驱动扣分 |
| R5 | 诊断本身不完整(中间字段被截断) | 中 | `path()` 防御性读取;dimensions 默认从 1.0 起,缺字段不引发 NaN;最坏情况触发 `QUALITY_SCORER_INTERNAL_ERROR` 闸门 |
| R6 | phase 3 让 Java 又承担复杂分析,与 phase 2 边界模糊 | 中 | scorer 严禁调 `ReportSummaryStore` / `ExternalAnalysisService` / `InternalStatisticsAnalyzer`;G4 grep 守约束;新规则只能扣分,不能新算指标 |
| R7 | 前端不知"分数 ≠ 业务好坏"展示出错 | 中 | RFC 在 §11(后续演进)预留前端集成 task,要求前端在显示 quality_score 旁必须同时显示 `warnings[]` 中的免责声明 |
| R8 | quality_score 计算耗时拖慢主请求 | 低 | 算法 O(checks + anomalies + warnings),典型 ≤ 5ms;不含 IO |
| R9 | 两 flag 同时启用导致诊断被调两次 | 低 | controller 拓扑保证单次调用(§8.3);C15 集成测试守约束 |
| R10 | schema 演进破坏前端 | 中 | `historical_quality.schema_version` 独立演进,小调整 v1.x,破坏性 v2.0;前端按 schema_version 判断 |

### 10.2 是否需要前端展示免责声明?

**需要**。第一版强制 `historical_quality.warnings` 必含两条,前端必须在 quality 卡片底部展示这两条 warning 的中文翻译(本 RFC 不强制翻译文案,留给前端 PR)。

### 10.3 是否需要文档明确性质?

**需要**。实施 PR 须同时更新两份文档:

- `docs/analysis/rfc-003-historical-quality-score.md` — 本文(已就位)
- 用户文档/API 文档(如 `docs/api/`,本 RFC 不强制创建)— 在 `historical_quality` 接口段落首行用粗体声明:
  > "本字段是数据质量与历史可比性评分,**不是**仿真结果优劣评分。"

---

## 十一、非目标(对应用户列出的)

phase 3 第一版**不做**:

- ❌ 数据库 / SQLite / 任何持久化扩展
- ❌ 机器学习 / 神经网络 / 任何不可解释模型
- ❌ 前端 jsx 改动(除阅读建议外)
- ❌ 修改 C++(`AnalysisCore.cpp` / `源.cpp`)
- ❌ 修改 fallback 统计(`InternalStatisticsAnalyzer`)
- ❌ 修改 `ReportSummaryStore` / `ReportSummaryExtractor`
- ❌ 重新扫描 `reports/*.json`
- ❌ 重新计算 bootstrap / 瓶颈 / ANOVA / 任何 phase 0 已有的统计量
- ❌ cross-scenario quality(批量接口零变化)
- ❌ 自动调参建议
- ❌ 业务优化建议(如"建议增加 2 个窗口")
- ❌ 把 quality_score 当排序依据
- ❌ 任何"好/坏"价值判断

---

## 十二、验收标准

实施完成后视为 phase 3 完成,需同时满足:

1. ✅ 默认请求(无 `include_historical_quality`)`/api/analysis/run` 响应**不含** `historical_quality` 子树。
2. ✅ `include_historical_quality=true` 时,响应顶层多 `historical_quality` 子树,字段集严格遵守 §6 schema。
3. ✅ `historical_quality` 在任何输入下**不出现** `quality_score` 之外的"业务评分"字段(无 excellent/good/bad/perfect/terrible 等等级名)。
4. ✅ `score_available=false` 时**不输出** `quality_score` / `quality_score_percent` / `dimensions` / `penalties`。
5. ✅ `quality_score ∈ [0,1]`,`quality_score_percent` 是 [0,100] 整数,无 NaN/Infinity;Q21+Q22 守约束。
6. ✅ `level ∈ {reliable, usable, caution, unreliable, unavailable}`,枚举值闭集。
7. ✅ `HistoricalQualityScorerTest` 全绿(Q1–Q25 ≥ 25 个 case)。
8. ✅ `AnalysisControllerIntegrationTest` 全绿(C1–C6 既有 + C7–C15 新增,共 ≥ 14 个 case)。
9. ✅ `mvn -DskipFrontend=true test` 全绿,数 ≥ 207 + 新增。
10. ✅ `cd sun && npm run build:backend` 通过;`ServedFrontendBundleFreshnessTest` 通过。
11. ✅ grep `HistoricalQualityScorer.java` 不出现:
    - `Paths.get("reports"`、`reports/`、`simulation-report-`、`simulation-history-`(除注释)
    - `ProcessBuilder`、`Runtime.exec`、`dataAnalyze`
    - `InternalStatisticsAnalyzer`、`ExternalAnalysisService`、`ReportSummaryStore`
12. ✅ phase 2 全部 24 测试 + phase 1 全部 24 测试零修改通过。
13. ✅ controller 在 quality 启用 + diagnostics 启用同请求中**只调一次** `diagnose`(C15 守)。
14. ✅ C++ binary、前端 jsx、`application.yml`、`pom.xml` 零变更。

---

## 十三、回滚方案

phase 3 完全在 phase 0/1/2 之外:

1. `AnalysisController` 中删除 `includeHistoricalQuality` 字段 + `maybeMergeQuality(...)` 调用,响应即恢复 phase 2 形态。
2. `HistoricalQualityScorer.java` 与测试可整文件删除,无其它引用。
3. 前端集成 PR(若已合)单独回滚,后端不受影响。

回滚成本与 phase 2 同等(单 PR revert 即可)。

---

## 十四、后续演进

> phase 3 v1 落地后的可选改进,**不在本轮** RFC 范围,仅备忘。

- v1.1:把 `dimensions` 抽成 DTO,phase 3 切换输入类型;不影响 schema。
- v1.2:加 `include_historical_quality_strict` flag,严格模式下把 missing/deleted/unverified 邻居排除出基线(打通 phase 2 RFC 中的 `policy.strict` 钩子)。
- v1.3:支持自定义扣分参数(从 `application.yml` 读),便于运维调阈值;但默认值不动。
- v2.0:若用户反馈 score 体系仍易误解,改为只输出 `level`,移除 `quality_score` / `quality_score_percent`(破坏性变更,与 phase 2 schema 解耦)。
- 跨场景 quality(`/api/analysis/cross-scenario` 启用):需独立 RFC,数据语义完全不同(per-batch vs per-report),不在 v1.x 内。

---

## 十五、最终回答(直答用户原始问题)

1. **phase 3 与 phase 2 的关系?** phase 3 是 phase 2 的**消费者**。phase 2 已给出全部所需事实(matched_reports / matching_strategy / anomalies / source_status_counts / checks / warnings),phase 3 只在它们之上做扣分制评分,不重做匹配/MAD/不变量检查。
2. **是否调用 HistoricalDiagnosticsService?** 是,但**通过 controller 调一次**,把同一份 ObjectNode 同时喂给 diagnostics 输出和 scorer,保证 basis 字段同源。
3. **是否新读 ReportSummaryStore?** 否。phase 3 不直接接触 store。
4. **scoring model 推荐?** 扣分制(A) + 规则前置闸门(C) 混合;每维度独立从 1.0 起扣,综合分 = `min(dimensions)`(保守);否决加权(B)以避免"看似精确"。
5. **quality_score 范围?** [0,1] 输出 2 位小数;同步输出 `quality_score_percent` 整数 [0,100]。
6. **quality_score 不可用时?** `score_available=false`,**不**输出 `quality_score` / `quality_score_percent` / `dimensions` / `penalties`,只输出 `level: "unavailable"` + `unavailable_reason`。
7. **level?** 始终输出。枚举:reliable / usable / caution / unreliable / unavailable。warning anomaly 把 level 上限封 `caution`。INVARIANT_FAILURE 把 level 强降到 `unreliable`。
8. **启用方式?** `include_historical_quality=true`(默认 false)。**不**自动启用 diagnostics 输出。两 flag 同 true 时同时输出且 controller 只调一次 diagnose。
9. **实现范围?** 新建 `HistoricalQualityScorer.java` + 测试;改 `AnalysisController` 加一个字段 + 一个合并方法 + 1 次同步调用拓扑。**不**改 phase 2 的 `HistoricalDiagnosticsService` 签名。
10. **是否抽 DTO?** 第一版**不抽**,scorer 直接读 ObjectNode,避开重构 phase 2。
11. **风险与缓解?** 见 §十,主要风险是"分数被误读为业务质量",通过始终输出 2 条免责 warning + 中性 level 词 + 文档声明三重缓解。

> **本 RFC 阶段:只设计,不写代码。**
> 实施轮触发条件:用户在阅读后明确批准并下达"进入 phase 3 实施轮"。
