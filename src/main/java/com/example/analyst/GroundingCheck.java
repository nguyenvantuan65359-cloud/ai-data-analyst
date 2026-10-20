package com.example.analyst;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数字出处校验：答案里每一个"像统计结果"的数字，都必须能在
 * 工具输出 / 图表取数结果 / 数据画像 里找到（允许四舍五入）。
 *
 * 与 ArithCheck 的分工：
 * - ArithCheck 管"算得对不对"（两个数相减/相除能否复现）；
 * - GroundingCheck 管"数字从哪来的"（是不是凭空出现的）。
 * 两者都过，才谈得上结论可复现。
 */
public final class GroundingCheck {

    /**
     * 数字切分：前一个字符不能是数字或小数点，后一个字符不能是数字。
     * 原来的写法会把 "0.691" 从中间切开、只剩 "691"，于是拿 691 去和 0.690727 比对，
     * 必然判成"找不到出处"——实测就是这么误报的。
     */
    private static final Pattern NUM = Pattern.compile(
            "(?<![0-9.])([0-9][0-9,]*(?:\\.[0-9]+)?)(?![0-9])");

    private GroundingCheck() {
    }

    public static List<String> check(String answer, String trace) {
        List<String> issues = new ArrayList<>();
        if (answer == null || trace == null || trace.isBlank()) return issues;

        Set<Double> sources = numbers(trace);
        if (sources.isEmpty()) return issues;

        // 答案自身出现的数字，用于判断"这个值是不是两个数的差/和算出来的派生值"。
        // 不去管派生值的好坏（那是 ArithCheck 的事），这里只找"完全凭空冒出来"的数字。
        Set<Double> selfNums = numbers(answer);

        List<String> missing = new ArrayList<>();
        for (String raw : numbersRaw(answer)) {
            double v = parse(raw);
            if (v < 100) continue;
            if (grounded(v, sources)) continue;
            if (derivable(v, selfNums)) continue;
            if (!missing.contains(raw)) missing.add(raw);
            if (missing.size() >= 4) break;
        }

        if (!missing.isEmpty()) {
            issues.add("以下数字在**本轮**工具输出、图表取数结果和数据画像里找不到出处：" + String.join("、", missing)
                    + "。若来自上一轮对话可忽略（出处校验只覆盖本轮）；若是自己算的派生值，请写出算式"
                    + "（例如 18,877 = 140,849 − 121,972）；否则请先用 SQL 或工具取得再引用。");
        }
        return issues;
    }

    /** 这个值能否由答案里另外两个数相加/相减得到（是派生值，交给 ArithCheck 管，不算"凭空出现"） */
    private static boolean derivable(double v, Set<Double> selfNums) {
        java.util.List<Double> l = new ArrayList<>(selfNums);
        double tol = Math.max(0.6, Math.abs(v) * 0.005);
        for (int i = 0; i < l.size(); i++) {
            for (int j = i + 1; j < l.size(); j++) {
                double x = l.get(i), y = l.get(j);
                if (Math.abs(Math.abs(x - y) - v) <= tol) return true;
                if (Math.abs(x + y - v) <= tol) return true;
            }
        }
        return false;
    }

    /** 允许四舍五入：相对误差 0.5% 或绝对误差 0.6 */
    private static boolean grounded(double v, Set<Double> sources) {
        double tol = Math.max(0.6, Math.abs(v) * 0.005);
        for (double s : sources) {
            if (Math.abs(s - v) <= tol) return true;
        }
        return false;
    }

    private static Set<Double> numbers(String text) {
        Set<Double> out = new LinkedHashSet<>();
        Matcher m = NUM.matcher(text);
        while (m.find()) {
            double v = parse(m.group(1));
            if (v >= 100) out.add(v);
        }
        return out;
    }

    private static List<String> numbersRaw(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = NUM.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s.replace(",", "").replace("，", ""));
        } catch (Exception e) {
            return -1;
        }
    }
}
