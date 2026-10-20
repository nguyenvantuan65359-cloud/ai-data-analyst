package com.example.analyst;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 代码级自检：不花钱、不调模型，只查那些"一看就不对"的硬伤。
 *
 * 为什么先做这一层：把能确定性判定的问题交给正则，比再问一次模型便宜得多，也不会瞎编。
 * 需要判断"方法选得对不对""口径有没有缺"这种语义问题的，交给 Verifier（模型复核）。
 */
public final class SelfCheck {

    /** 出现"显著"这类断言时，正文必须给出 p 值，否则就是把描述当结论 */
    private static final Pattern CLAIM_SIGNIFICANT = Pattern.compile("显著|显著性检验通过|拒绝原假设");
    private static final Pattern HAS_P_VALUE = Pattern.compile("p\\s*[=＝<]\\s*0?\\.\\d+|p\\s*值");

    private SelfCheck() {
    }

    public static List<String> check(AnalystResult r) {
        List<String> issues = new ArrayList<>();
        String a = r == null || r.answer() == null ? "" : r.answer();

        if (a.isBlank()) {
            issues.add("答案是空的：没有给出任何结论。");
            return issues;
        }
        if (a.length() < 80) {
            issues.add("回答过短（" + a.length() + " 字），缺少必要的论证过程。");
        }
        if (!a.contains("结论")) {
            issues.add("缺少「结论」段落，读者看不到直接答案。");
        }
        if (!a.contains("局限")) {
            issues.add("缺少「局限与提醒」段落：没有交代样本量、口径或因果边界。");
        }
        if (CLAIM_SIGNIFICANT.matcher(a).find() && !HAS_P_VALUE.matcher(a).find()) {
            issues.add("正文出现了「显著」这类断言，但没有给出 p 值或检验统计量——显著性不能靠形容词。");
        }
        return issues;
    }
}
