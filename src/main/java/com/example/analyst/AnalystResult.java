package com.example.analyst;

import java.util.List;
import java.util.Map;

/**
 * 智能体一次分析的产出：
 * - answer：给人类看的中文结论（含方法依据与统计学理论）
 * - charts：一张问题可能需要的**多张**图表，每项 = 图型规格 + 已执行好的数据（可含 error）
 * - chart / chartData：为兼容老前端保留，等价于 charts 的第一项
 * - cached：true 表示这次结果命中了问答缓存（同一问题+数据态短时间内复用）
 * - plan：这次分析的计划步骤（自主规划模式下有值，前端可展开看它打算怎么干）
 * - attempts：总共执行了几轮（>1 说明被自检打回过，第 N 轮才通过）
 */
public record AnalystResult(String answer,
                            Map<String, Object> chart,
                            Map<String, Object> chartData,
                            List<Map<String, Object>> charts,
                            boolean cached,
                            List<String> plan,
                            int attempts) {

    public static AnalystResult textOnly(String answer) {
        return new AnalystResult(answer, null, null, List.of(), false, List.of(), 0);
    }

    /** 缓存命中时返回一份标注 cached=true 的副本（不修改原对象） */
    public AnalystResult withCached(boolean flag) {
        return flag == this.cached ? this :
                new AnalystResult(answer, chart, chartData, charts, flag, plan, attempts);
    }

    public AnalystResult withPlan(List<String> p, int n) {
        return new AnalystResult(answer, chart, chartData, charts, cached, p, n);
    }
}
