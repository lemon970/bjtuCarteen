package com.bjtu.simulation.dto;

import java.util.List;

public class WaitTimeInsight {
    private final String status;
    private final String primaryReason;
    private final List<String> secondaryReasons;
    private final String baselineRange;
    private final String message;

    public WaitTimeInsight(String status,
                           String primaryReason,
                           List<String> secondaryReasons,
                           String baselineRange,
                           String message) {
        this.status = status == null || status.isBlank() ? "normal" : status;
        this.primaryReason = primaryReason == null ? "" : primaryReason;
        this.secondaryReasons = secondaryReasons == null ? List.of() : List.copyOf(secondaryReasons);
        this.baselineRange = baselineRange == null ? "0-5 分钟正常，5-10 分钟轻度拥堵，10-15 分钟明显拥堵，15 分钟以上严重排队" : baselineRange;
        this.message = message == null ? "" : message;
    }

    public static WaitTimeInsight empty() {
        return new WaitTimeInsight(
                "normal",
                "暂无等待样本",
                List.of("当前仿真没有完成服务的学生，等待体验指标保持为 0。"),
                null,
                "暂无可分析的等待时间样本。");
    }

    public String getStatus() {
        return status;
    }

    public String getPrimaryReason() {
        return primaryReason;
    }

    public List<String> getSecondaryReasons() {
        return secondaryReasons;
    }

    public String getBaselineRange() {
        return baselineRange;
    }

    public String getMessage() {
        return message;
    }
}
