package com.example.analyst;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 算术复算：专治"方向对、数字错"。
 *
 * 为什么需要它：实测里模型最容易翻的车不是方法选错，而是**心算派生指标**——
 * 两个真实数字都在正文里，写出来的差值和百分比却是错的（18,002 该是 20,358；
 * 15.5% 的分母没交代）。这类错误最难被发现：结论读起来很顺，图上也是对的。
 *
 * 做法（纯代码，不花钱）：
 * 1. 把答案里出现的所有数字收进来当"可复现的素材"；
 * 2. 找出"多/少了 X 元"这类差值断言，看能否用素材里的两个数相减复现；
 * 3. 找出"比…增长 Y%"这类百分比断言，看能否用素材里的两个数复现（两种分母都试）。
 * 复现不出来就记一条：要么算错了，要么没交代分母。
 */
public final class ArithCheck {

    /** 差值断言：动词紧跟数字，如"多 18,002 元""低了 3.2 个百分点" */
    private static final Pattern DELTA = Pattern.compile(
            "(?:多|少了?|高出?|低了?|领先|落后|增加|减少|增长|下降)了?\\s*(?:约)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)");

    /** 百分比断言 */
    private static final Pattern PERCENT = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*%");

    /** 任意数字（含千分位） */
    private static final Pattern NUMBER = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?)");

    private ArithCheck() {
    }

    public static List<String> check(String answer) {
        List<String> issues = new ArrayList<>();
        if (answer == null || answer.length() < 40) return issues;

        Set<Double> nums = numbersIn(answer);
        if (nums.size() < 3) return issues;          // 素材太少，没法复算，别瞎报

        List<Double> list = new ArrayList<>(nums);

        // ---- 1) 差值断言 ----
        for (String sent : sentences(answer)) {
            Matcher d = DELTA.matcher(sent);
            while (d.find()) {
                double claimed = parse(d.group(1));
                if (claimed < 10) continue;           // 太小容易撞车，不查
                if (reproducibleDelta(list, claimed)) continue;
                issues.add("差值复算不出来：正文写「" + clip(sent) + "」，但用文中出现的数字两两相减，"
                        + "没有任何一对的差接近 " + fmt(claimed) + "。"
                        + "请改用 SQL 算出差值再引用，或写出算式与两个原始数字。");
                if (issues.size() >= 2) return issues;
            }
        }

        // ---- 2) 百分比断言（只在"比/较/相对"语境里查） ----
        for (String sent : sentences(answer)) {
            if (!sent.contains("比") && !sent.contains("较") && !sent.contains("相对")) continue;
            Matcher p = PERCENT.matcher(sent);
            while (p.find()) {
                double claimed = parse(p.group(1));
                if (claimed <= 0 || claimed > 500) continue;
                if (reproduciblePercent(list, claimed)) continue;
                issues.add("百分比复算不出来：正文写「" + clip(sent) + "」，但用文中数字按 (大-小)/小 和 (大-小)/大 "
                        + "两种口径都算不出 " + fmt(claimed) + "%。请明确写出分母，或用 SQL 直接算占比/增幅。");
                if (issues.size() >= 2) return issues;
            }
        }
        return issues;
    }

    /** 是否存在一对数字，其差的绝对值≈claimed（容差 2% 或 1 个单位） */
    private static boolean reproducibleDelta(List<Double> nums, double claimed) {
        double tol = Math.max(1.0, Math.abs(claimed) * 0.02);
        for (int i = 0; i < nums.size(); i++) {
            for (int j = i + 1; j < nums.size(); j++) {
                if (Math.abs(Math.abs(nums.get(i) - nums.get(j)) - claimed) <= tol) return true;
            }
        }
        return false;
    }

    /** 是否存在一对数字，按 (大-小)/小 或 (大-小)/大 能算出 claimed% */
    private static boolean reproduciblePercent(List<Double> nums, double claimed) {
        for (int i = 0; i < nums.size(); i++) {
            for (int j = i + 1; j < nums.size(); j++) {
                double a = nums.get(i), b = nums.get(j);
                double big = Math.max(a, b), small = Math.min(a, b);
                if (small <= 0 || big <= 0) continue;
                double p1 = (big - small) / small * 100.0;
                double p2 = (big - small) / big * 100.0;
                double tol = Math.max(0.6, claimed * 0.04);
                if (Math.abs(p1 - claimed) <= tol || Math.abs(p2 - claimed) <= tol) return true;
            }
        }
        return false;
    }

    private static Set<Double> numbersIn(String text) {
        Set<Double> out = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            double v = parse(m.group(1));
            // 只收 >=100 的数：百分数（12.4）、月份（1-6）、类别编号这种小数一旦进池，
            // 会跟被校验的差值凑出"貌似能复现"的假匹配（实测 18,002 被 18,002 与 10.9 的差蒙混过关）。
            if (v >= 100) out.add(v);
        }
        return out;
    }

    /** 按中文/英文句读切句，保留小句，避免跨句配对 */
    private static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("[。；;\\n]|(?<=[）)])")) {
            String t = s.trim();
            if (t.length() >= 6) out.add(t);
        }
        return out;
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s.replace(",", "").replace("，", ""));
        } catch (Exception e) {
            return -1;
        }
    }

    private static String fmt(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-9
                ? String.valueOf((long) v)
                : String.format("%.2f", v);
    }

    private static String clip(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }
}
