package com.example.analyst;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次分析的执行计划：先想清楚"要分几步、每步用什么方法、什么算完成"，再动手取数。
 *
 * 为什么要有它：单轮工具调用时，模型是"边想边查"，容易查到哪算哪——
 * 数据不支撑就换个角度凑，最后给出一堆彼此不咬合的数字。
 * 先出一份可读的计划，人能在日志里看见它打算怎么干，也能看出它压根没打算校验什么。
 */
public record AnalysisPlan(String goal, List<Step> steps, String acceptance) {

    /** 一步：做什么、用什么方法、预期拿到什么 */
    public record Step(int id, String task, String method, String expects) {
    }

    public static AnalysisPlan fallback(String question) {
        return new AnalysisPlan(
                question,
                List.of(new Step(1, "直接回答用户问题", "按数据画像选择合适方法", "可核对的数字与图")),
                "结论中的数字必须来自工具输出");
    }

    /** 给提示词/前端看的一行行文本 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("目标：").append(goal == null ? "" : goal).append("\n");
        if (steps != null) {
            for (Step s : steps) {
                sb.append(s.id()).append(". ").append(s.task());
                if (s.method() != null && !s.method().isBlank()) sb.append("｜方法：").append(s.method());
                if (s.expects() != null && !s.expects().isBlank()) sb.append("｜预期：").append(s.expects());
                sb.append("\n");
            }
        }
        if (acceptance != null && !acceptance.isBlank()) sb.append("验收标准：").append(acceptance);
        return sb.toString().trim();
    }

    public List<String> lines() {
        List<String> out = new ArrayList<>();
        if (steps != null) {
            for (Step s : steps) {
                out.add(s.id() + ". " + s.task() + (s.method() == null || s.method().isBlank() ? "" : "（" + s.method() + "）"));
            }
        }
        return out;
    }
}
