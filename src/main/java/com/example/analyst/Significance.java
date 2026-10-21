package com.example.analyst;

import org.apache.commons.math3.stat.inference.TTest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 显著性检验层。**纯 Java，不经过模型**。
 *
 * 为什么不让模型选检验方法：选方法是查表，不是推理。交给模型，同一个问题问两次可能给不同答案，
 * 而且它会为选择编一个听起来很专业的前提（这个项目里已经发生过一次：
 * 它声称"p 值由 SQL 按 F 分布密度函数数值积分求得"，而 H2 根本没有这个函数）。
 *
 * 三条纪律：
 * 1. **全量检验 + BH 校正**，而不是只对挑出来的 Top N 检验。从几百个切片里挑最大的那个再验证它，
 *    p 值必然偏小（winner's curse）——先挑后验，数学上注定显著。
 * 2. **比率指标不用 t**。单价 = 金额/订单，分子分母都随机，对行级比值做 t 会低估方差，
 *    算出偏小的 p 值。这里改用置换检验（permutation）直接比较两组的比值。
 * 3. **样本不够就不检验**，明确返回原因，不允许拿"稳健 z 很大"冒充显著。
 */
public final class Significance {

    /** 目标组最少样本：低于这个数，t 检验的正态近似不可靠 */
    private static final int MIN_TARGET_N = 5;
    /** 参照组最少样本 */
    private static final int MIN_REF_N = 8;
    /** 置换次数：1000 次时 p 的分辨率约 0.001，足够做 FDR */
    private static final int PERMUTATIONS = 1000;

    private static final Random RND = new Random(20261021L);   // 固定种子：同一份数据结果可复现

    private static final TTest T = new TTest();

    private Significance() {
    }

    /** 检验结果。ok=false 时 p 无意义，reason 说明为什么不能检验 */
    public static class Result {
        public final boolean ok;
        public final double p;
        public final String method;
        public final String reason;

        private Result(boolean ok, double p, String method, String reason) {
            this.ok = ok;
            this.p = p;
            this.method = method;
            this.reason = reason;
        }

        static Result of(double p, String method) {
            return new Result(true, p, method, "");
        }

        static Result skip(String reason) {
            return new Result(false, Double.NaN, "", reason);
        }
    }

    /**
     * 连续指标：Welch t（不假设方差齐性）。
     * 用 Welch 而不是合并方差 t，是因为业务数据里两组方差几乎总是不等的，
     * 而 Welch 在方差相等时也只损失极小的功效。
     */
    public static Result welch(double[] target, double[] reference, String contrast) {
        int na = validCount(target), nb = validCount(reference);
        if (na < MIN_TARGET_N) {
            return Result.skip("目标组只有 " + na + " 条（需 ≥" + MIN_TARGET_N + "）");
        }
        if (nb < MIN_REF_N) {
            return Result.skip("参照组只有 " + nb + " 条（需 ≥" + MIN_REF_N + "）");
        }
        double[] a = clean(target), b = clean(reference);
        try {
            double p = T.tTest(a, b);
            if (Double.isNaN(p)) return Result.skip("两组方差均为 0，无法比较");
            return Result.of(p, "Welch t（" + contrast + "）");
        } catch (Exception e) {
            return Result.skip("检验不适用：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 比率指标：置换检验。
     *
     * 单价这类指标不能对行级比值做 t：分母（订单数）本身是随机的，
     * 小分母那几行会产生极端比值，t 检验的标准误完全失真。
     * 做法是把两组样本打乱重分，重算 SUM(分子)/SUM(分母)，看观测到的差有多少次能被随机复现。
     */
    public static Result ratioPermutation(List<double[]> target, List<double[]> reference, String contrast) {
        if (target.size() < MIN_TARGET_N || reference.size() < MIN_REF_N) {
            return Result.skip("目标组 " + target.size() + " 行、参照组 " + reference.size()
                    + " 行，样本不足（需 ≥" + MIN_TARGET_N + "/" + MIN_REF_N + "）");
        }
        double observed = ratio(target) - ratio(reference);
        if (Double.isNaN(observed)) return Result.skip("分母为 0，比值无意义");

        List<double[]> pooled = new ArrayList<>(target);
        pooled.addAll(reference);
        int nt = target.size();

        int hits = 0;
        for (int k = 0; k < PERMUTATIONS; k++) {
            // 打乱后按原组大小重新切分：零假设下"属于哪组"应当无关
            java.util.Collections.shuffle(pooled, RND);
            List<double[]> a = pooled.subList(0, nt);
            List<double[]> b = pooled.subList(nt, pooled.size());
            double diff = ratio(a) - ratio(b);
            if (Math.abs(diff) >= Math.abs(observed)) hits++;
        }
        // +1 校正：避免 p=0（1000 次置换的分辨率下限就是 1/1001）
        double p = (hits + 1.0) / (PERMUTATIONS + 1.0);
        return Result.of(p, "置换检验 ×" + PERMUTATIONS + "（" + contrast + "，比值指标）");
    }

    private static double ratio(List<double[]> rows) {
        double num = 0, den = 0;
        for (double[] r : rows) {
            num += r[0];
            den += r[1];
        }
        return den == 0 ? Double.NaN : num / den;
    }

    /**
     * Benjamini-Hochberg：把一组 p 值转成 q 值（控制假发现率）。
     *
     * 这一步是"自动扫描"能不能被信任的分水岭。扫 500 个切片按 p<0.05 判，
     * 期望会冒出 25 个纯噪声——上线第三天就没用户了。
     * q 值的含义是"我报的这一批里，预计有 q 比例是假的"。
     */
    public static double[] bh(double[] p) {
        int m = p.length;
        double[] q = new double[m];
        Integer[] idx = new Integer[m];
        for (int i = 0; i < m; i++) idx[i] = i;
        Arrays.sort(idx, (x, y) -> Double.compare(p[x], p[y]));

        double prev = 1.0;
        // 从最大的 p 往回走，保证 q 单调不减（否则会出现"p 更大却 q 更小"的荒谬结果）
        for (int k = m - 1; k >= 0; k--) {
            int i = idx[k];
            double val = p[i] * m / (k + 1.0);
            prev = Math.min(prev, val);
            q[i] = Math.min(1.0, prev);
        }
        return q;
    }

    private static int validCount(double[] a) {
        int n = 0;
        for (double v : a) if (!Double.isNaN(v) && !Double.isInfinite(v)) n++;
        return n;
    }

    private static double[] clean(double[] a) {
        double[] tmp = new double[validCount(a)];
        int k = 0;
        for (double v : a) {
            if (!Double.isNaN(v) && !Double.isInfinite(v)) tmp[k++] = v;
        }
        return tmp;
    }
}
