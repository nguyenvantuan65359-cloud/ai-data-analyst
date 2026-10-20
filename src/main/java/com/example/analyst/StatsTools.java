package com.example.analyst;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.correlation.SpearmansCorrelation;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.inference.ChiSquareTest;
import org.apache.commons.math3.stat.inference.KolmogorovSmirnovTest;
import org.apache.commons.math3.stat.inference.OneWayAnova;
import org.apache.commons.math3.stat.inference.TTest;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 统计分析工具库，封装成 @Tool 交给 LLM 调度。
 *
 * 三条硬规矩（踩过坑后定下来的）：
 *   1) 所有数字必须是这里算出来的——LLM 不得自行推测 p 值、F 值。提示词里承诺的方法，这里必须真的存在。
 *   2) 每个统计输出都带数据质量行：总行数 / 有效数 / 丢弃数 / 缺失率。样本悄悄变小是不能接受的。
 *   3) 前提假设要"验"不要"猜"：正态性、方差比、期望频数由代码判断，模型只能引用结论。
 *
 * 会话隔离：每个工具方法额外接收 Spring AI 注入的 ToolContext，取出 sessionId，只读写本会话的表。
 */
@Component
public class StatsTools {

    private final AnalysisSession session;
    private final ToolBudget budget;

    public StatsTools(AnalysisSession session, ToolBudget budget) {
        this.session = session;
        this.budget = budget;
    }

    // ==================== 描述统计（含数据质量与分布形态）====================

    @Tool(description = "描述统计：样本量、缺失率、均值、标准差、分位数、偏度、峰度、异常值数、正态性参考。看任何一列都要先跑这个。")
    public String describe(@ToolParam(description = "列名") String column, ToolContext ctx) {
        DataTable t = require(ctx);
        int i = resolve(t, column);
        Quality q = quality(t.rowCount(), t.columnAsDouble(i));
        if (q.values.length == 0) return "列[" + column + "] 没有可用数值，无法统计。";

        DescriptiveStatistics s = new DescriptiveStatistics();
        for (double d : q.values) s.addValue(d);
        double p25 = s.getPercentile(25), p75 = s.getPercentile(75), iqr = p75 - p25;
        int outliers = 0;
        double lo = p25 - 1.5 * iqr, hi = p75 + 1.5 * iqr;
        for (double d : q.values) if (d < lo || d > hi) outliers++;

        return String.format(
                "列[%s] %s%n均=%.3f 标准=%.3f 变异系数CV=%.3f%n最小=%.3f p1=%.3f p25=%.3f 中位=%.3f p75=%.3f p99=%.3f 最大=%.3f%n偏度=%.3f 峰度=%.3f (0=正态)%nIQR异常值: %d 个 (%.1f%%)%n%s",
                column, q.line(), s.getMean(), s.getStandardDeviation(),
                safeCv(s.getMean(), s.getStandardDeviation()),
                s.getMin(), s.getPercentile(1), p25, s.getPercentile(50), p75, s.getPercentile(99), s.getMax(),
                s.getSkewness(), s.getKurtosis(),
                outliers, q.values.length == 0 ? 0 : outliers * 100.0 / q.values.length,
                normality(q.values, s.getMean(), s.getStandardDeviation()));
    }

    // ==================== 相关性 ====================

    @Tool(description = "Pearson 线性相关：r、样本量、p 值、95% 置信区间。判断两列数值线性相关强弱与方向。")
    public String correlation(@ToolParam(description = "第一列名") String colA,
                              @ToolParam(description = "第二列名") String colB, ToolContext ctx) {
        DataTable t = require(ctx);
        Pair p = pair(t, colA, colB);
        if (p.a.length < 3) return "有效配对不足（至少 3 对，当前 " + p.a.length + " 对），无法计算相关。";
        double r = new PearsonsCorrelation().correlation(p.a, p.b);
        int n = p.a.length;
        return String.format("Pearson r(%s, %s) = %.3f，%s，95%%CI=[%.3f, %.3f]%n%s",
                colA, colB, r, pvalueOfCorrelation(r, n), ciLow(r, n), ciHigh(r, n), p.line());
    }

    @Tool(description = "Spearman 秩相关：ρ、样本量、p 值（近似）。数据不满足线性/正态、或存在极端值时用它替代 Pearson。")
    public String spearman(@ToolParam(description = "第一列名") String colA,
                           @ToolParam(description = "第二列名") String colB, ToolContext ctx) {
        DataTable t = require(ctx);
        Pair p = pair(t, colA, colB);
        if (p.a.length < 4) return "有效配对不足（至少 4 对，当前 " + p.a.length + " 对），无法计算秩相关。";
        double rho = new SpearmansCorrelation().correlation(p.a, p.b);
        int n = p.a.length;
        return String.format("Spearman ρ(%s, %s) = %.3f，%s（单调关系强度，非线性也能捕捉）%n%s",
                colA, colB, rho, pvalueOfCorrelation(rho, n), p.line());
    }

    // ==================== 均值差异检验 ====================

    @Tool(description = "Welch 独立样本 t 检验：两组均值是否显著不同。返回各自均值/样本量、t、p、Cohen's d、方差比与正态性参考。")
    public String tTest(@ToolParam(description = "组A列名（数值）") String colA,
                        @ToolParam(description = "组B列名（数值）") String colB, ToolContext ctx) {
        DataTable t = require(ctx);
        double[] a = clean(nums(t, colA));
        double[] b = clean(nums(t, colB));
        if (a.length < 2 || b.length < 2) return "样本不足（A 有效 " + a.length + "，B 有效 " + b.length + "）。";

        DescriptiveStatistics sa = new DescriptiveStatistics();
        for (double d : a) sa.addValue(d);
        DescriptiveStatistics sb = new DescriptiveStatistics();
        for (double d : b) sb.addValue(d);

        double p = new TTest().tTest(a, b);
        double pooled = Math.sqrt(((a.length - 1) * var(a) + (b.length - 1) * var(b))
                / (double) (a.length + b.length - 2));
        double d = pooled == 0 ? Double.NaN : (sa.getMean() - sb.getMean()) / pooled;

        return String.format(
                "Welch t 检验 %s vs %s：p=%.4f（%s，α=0.05）%n  %s：n=%d 均值=%.3f 标准差=%.3f%n  %s：n=%d 均值=%.3f 标准差=%.3f%n"
                        + "差异=%.3f Cohen's d=%.3f（%s）%n方差比=%.2f（>2 提示方差不齐，Welch 已自动修正）%n%s%n%s",
                colA, colB, p, p < 0.05 ? "差异显著" : "差异不显著",
                colA, a.length, sa.getMean(), sa.getStandardDeviation(),
                colB, b.length, sb.getMean(), sb.getStandardDeviation(),
                sa.getMean() - sb.getMean(), d, effect(d),
                maxVarRatio(a, b),
                normality(a, sa.getMean(), sa.getStandardDeviation()),
                normality(b, sb.getMean(), sb.getStandardDeviation()));
    }

    @Tool(description = "单因素方差分析 ANOVA：三组及以上均值是否有显著差异。返回 F、p、各组样本量与均值。")
    public String anova(@ToolParam(description = "分组列名（类别）") String groupCol,
                        @ToolParam(description = "数值列名") String valueCol, ToolContext ctx) {
        DataTable t = require(ctx);
        int g = resolve(t, groupCol), v = resolve(t, valueCol);
        Map<String, List<Double>> groups = new LinkedHashMap<>();
        int skipped = 0;
        for (List<Object> row : t.rows) {
            Object key = row.get(g);
            Double val = toDouble(row.get(v));
            if (key == null || val == null) { skipped++; continue; }
            groups.computeIfAbsent(String.valueOf(key), k -> new ArrayList<>()).add(val);
        }
        List<double[]> data = new ArrayList<>();
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<String, List<Double>> e : groups.entrySet()) {
            if (e.getValue().size() < 2) { skipped += e.getValue().size(); continue; }
            double[] arr = new double[e.getValue().size()];
            for (int i = 0; i < arr.length; i++) arr[i] = e.getValue().get(i);
            data.add(arr);
            DescriptiveStatistics ds = new DescriptiveStatistics();
            for (double d : arr) ds.addValue(d);
            detail.append(String.format("  %s：n=%d 均值=%.3f%n", e.getKey(), arr.length, ds.getMean()));
        }
        if (data.size() < 2) return "有效分组不足（至少需要 2 组且每组 ≥2 条），当前 " + data.size() + " 组。";
        OneWayAnova ov = new OneWayAnova();
        double f = ov.anovaFValue(data), p = ov.anovaPValue(data);
        // ANOVA 前提：各组方差齐（用最大/最小方差比粗判）
        double minVar = Double.MAX_VALUE, maxVar = 0;
        for (double[] arr : data) {
            double vv = var(arr);
            minVar = Math.min(minVar, vv);
            maxVar = Math.max(maxVar, vv);
        }
        double ratio = minVar == 0 ? Double.NaN : maxVar / minVar;
        return String.format("单因素 ANOVA %s ~ 分组 %s：F=%.3f p=%.4f（%s）%n%s丢弃=无分组/非数值 %d 条%n"
                        + "前提参考：各组方差比=%.2f（>3 提示方差不齐，结论需谨慎）",
                valueCol, groupCol, f, p, p < 0.05 ? "至少一组均值不同" : "各组均值无显著差异",
                detail, skipped, ratio);
    }

    // ==================== 类别分布 ====================

    @Tool(description = "卡方独立性检验：两个类别变量是否独立（如地区 与 是否退货）。返回 χ²、p 值、样本量、期望频数是否够。")
    public String chiSquare(@ToolParam(description = "类别列名A") String colA,
                            @ToolParam(description = "类别列名B") String colB, ToolContext ctx) {
        DataTable t = require(ctx);
        int ia = resolve(t, colA), ib = resolve(t, colB);
        Map<String, Integer> ra = new LinkedHashMap<>(), rb = new LinkedHashMap<>();
        Map<String, TreeMap<String, Long>> table = new LinkedHashMap<>();
        for (List<Object> row : t.rows) {
            Object a = row.get(ia), b = row.get(ib);
            if (a == null || b == null) continue;
            String ka = String.valueOf(a), kb = String.valueOf(b);
            ra.computeIfAbsent(ka, k -> ra.size());
            rb.computeIfAbsent(kb, k -> rb.size());
            table.computeIfAbsent(ka, k -> new TreeMap<>()).merge(kb, 1L, Long::sum);
        }
        int rowsN = ra.size(), colsN = rb.size();
        if (rowsN < 2 || colsN < 2) return "类别数不足（需要两个变量各有 2 个以上取值，当前 " + rowsN + " × " + colsN + "）。";
        long[][] counts = new long[rowsN][colsN];
        long total = 0;
        for (Map.Entry<String, TreeMap<String, Long>> e : table.entrySet()) {
            for (Map.Entry<String, Long> c : e.getValue().entrySet()) {
                counts[ra.get(e.getKey())][rb.get(c.getKey())] += c.getValue();
                total += c.getValue();
            }
        }
        ChiSquareTest cs = new ChiSquareTest();
        double chi2 = cs.chiSquare(counts), p = cs.chiSquareTest(counts);
        double minExpected = minExpected(counts, total);
        return String.format("卡方独立性检验 %s × %s：χ²=%.3f p=%.4f（%s），样本 n=%d，列联表 %d×%d%n前提：最小期望频数=%.1f（<5 时 χ² 近似不可靠，需合并类别或改用 Fisher 精确检验）",
                colA, colB, chi2, p, p < 0.05 ? "两变量不独立（有关联）" : "未发现关联",
                total, rowsN, colsN, minExpected);
    }

    // ==================== 回归 ====================

    @Tool(description = "简单线性回归 y~x：斜率、截距、R²、斜率 95% 置信区间、p 值。用一个变量解释另一个时用。")
    public String regression(@ToolParam(description = "自变量列名x") String xCol,
                             @ToolParam(description = "因变量列名y") String yCol,
                             ToolContext ctx) {
        DataTable t = require(ctx);
        Pair p = pair(t, xCol, yCol);
        if (p.a.length < 3) return "有效配对不足（至少 3 对，当前 " + p.a.length + " 对）。";
        SimpleRegression reg = new SimpleRegression();
        for (int i = 0; i < p.a.length; i++) reg.addData(p.a[i], p.b[i]);
        double slope = reg.getSlope(), alpha = 0.05;
        String ci;
        try {
            double half = reg.getSlopeConfidenceInterval(alpha) / 2;
            ci = String.format("[%.4f, %.4f]", slope - half, slope + half);
        } catch (Exception e) {
            ci = "样本过小，置信区间不可算";
        }
        double pv;
        try { pv = reg.getSignificance(); } catch (Exception e) { pv = Double.NaN; }
        return String.format("回归 %s ~ %s：斜率=%.4f 截距=%.4f R²=%.4f%n斜率 95%%CI=%s，p=%.4f（%s）%n%s%n提醒：回归只说明伴随关系，不等于因果",
                yCol, xCol, slope, reg.getIntercept(), reg.getRSquare(), ci, pv,
                (pv < 0.05 ? "斜率显著不为 0" : "斜率不显著"), p.line());
    }

    // ==================== 集中性分析（帕累托 / ABC）====================

    @Tool(description = "帕累托分析：按某列类目降序汇总，给出每个类目占比与累计占比，并指出进入前 80% 的类目。")
    public String pareto(@ToolParam(description = "类目列名") String categoryCol,
                         @ToolParam(description = "数值列名") String valueCol, ToolContext ctx) {
        DataTable t = require(ctx);
        Agg agg = aggregate(t, categoryCol, valueCol);
        if (agg.isEmpty()) return "没有可用于汇总的数值。";
        if (agg.rows.size() > 30) return agg.rows.size() + " 个类目过多，请先用 SQL 合并小类目再跑帕累托。";
        StringBuilder sb = new StringBuilder(String.format("帕累托（%s 按 %s 降序，合计=%.3f）%n", categoryCol, valueCol, agg.total));
        double cum = 0;
        int hit80 = -1, k = 0;
        for (Agg.Row r : agg.rows) {
            cum += r.sum;
            k++;
            double pct = r.sum / agg.total, cumPct = cum / agg.total;
            if (hit80 < 0 && cumPct >= 0.8) hit80 = k;
            sb.append(String.format("  %d. %s = %.3f (%.1f%%) 累计 %.1f%%%n", k, r.key, r.sum, pct * 100, cumPct * 100));
        }
        sb.append(String.format("累计达到 80%% 需要前 %d 个类目（共 %d 个，占 %.0f%%）",
                hit80 < 0 ? agg.rows.size() : hit80, agg.rows.size(),
                (hit80 < 0 ? agg.rows.size() : hit80) * 100.0 / agg.rows.size()));
        return sb.toString();
    }

    @Tool(description = "ABC 分类：按贡献累计占比把类目分成 A(前70%)/B(70-90%)/C(其余)，用于抓重点少数。")
    public String abc(@ToolParam(description = "类目列名") String categoryCol,
                      @ToolParam(description = "数值列名") String valueCol, ToolContext ctx) {
        DataTable t = require(ctx);
        Agg agg = aggregate(t, categoryCol, valueCol);
        if (agg.isEmpty()) return "没有可用于汇总的数值。";
        int a = 0, b = 0, c = 0;
        double cum = 0, valA = 0, valB = 0, valC = 0;
        for (Agg.Row r : agg.rows) {
            cum += r.sum;
            double share = cum / agg.total;
            if (share <= 0.7) { a++; valA += r.sum; }
            else if (share <= 0.9) { b++; valB += r.sum; }
            else { c++; valC += r.sum; }
        }
        return String.format("ABC 分类（%s 按 %s）%nA 类 %d 个，贡献 %.1f%%；B 类 %d 个，贡献 %.1f%%；C 类 %d 个，贡献 %.1f%%%n合计 %d 个类目，总金额=%.3f",
                categoryCol, valueCol, a, share(valA, agg.total), b, share(valB, agg.total),
                c, share(valC, agg.total), agg.rows.size(), agg.total);
    }

    // ==================== 时间序列 ====================

    @Tool(description = "时间序列分析：按年月聚合给定日期列与数值列，给出环比 MoM、同比 YoY、三期移动平均、趋势斜率与季节指数。看趋势、淡旺季用这个。")
    public String timeSeries(@ToolParam(description = "日期列名") String dateCol,
                             @ToolParam(description = "数值列名") String valueCol,
                             ToolContext ctx) {
        DataTable t = require(ctx);
        int di = resolve(t, dateCol), vi = resolve(t, valueCol);
        TreeMap<String, double[]> monthly = new TreeMap<>();  // YYYY-MM -> {sum, count}
        int bad = 0, missing = 0;
        for (List<Object> row : t.rows) {
            LocalDate d = toDate(row.get(di));
            Double v = toDouble(row.get(vi));
            if (d == null) { bad++; continue; }
            if (v == null) { missing++; continue; }
            String key = d.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            monthly.computeIfAbsent(key, k -> new double[]{0, 0});
            double[] slot = monthly.get(key);
            slot[0] += v;
            slot[1]++;
        }
        if (monthly.isEmpty()) return "日期列[" + dateCol + "] 无法解析为年月（不可解析 " + bad + " 条），请确认该列是日期类型。";

        List<String> keys = new ArrayList<>(monthly.keySet());
        List<Double> series = new ArrayList<>();
        for (String k : keys) series.add(monthly.get(k)[0]);

        StringBuilder sb = new StringBuilder(String.format("时间序列（%s 聚合 %s，共 %d 期，日期不可解析 %d 条 / 数值缺失 %d 条）%n",
                dateCol, valueCol, keys.size(), bad, missing));
        int from = Math.max(0, keys.size() - 24);
        for (int i = from; i < keys.size(); i++) {
            double cur = series.get(i);
            String mom = i > 0 ? pct(cur, series.get(i - 1)) : "—";
            String yoy = i >= 12 ? pct(cur, series.get(i - 12)) : "—";
            double ma = movingAverage(series, i, 3);
            sb.append(String.format("  %s %.2f  环比 %s  同比 %s  MA3=%.2f%n",
                    keys.get(i), cur, mom, yoy, ma));
        }
        SimpleRegression trend = new SimpleRegression();
        for (int i = 0; i < series.size(); i++) trend.addData(i, series.get(i));
        sb.append(String.format("趋势：斜率=%.3f/期，R²=%.3f（%s）%n",
                trend.getSlope(), trend.getRSquare(),
                Math.abs(trend.getRSquare()) > 0.5 ? (trend.getSlope() > 0 ? "整体上升" : "整体下降") : "趋势不明显（R² 偏低）"));

        // 季节指数：同月份均值 / 总体月均
        Map<String, double[]> byMonth = new TreeMap<>();
        for (int i = 0; i < keys.size(); i++) {
            String m = keys.get(i).substring(5);
            byMonth.computeIfAbsent(m, k -> new double[]{0, 0});
            byMonth.get(m)[0] += series.get(i);
            byMonth.get(m)[1]++;
        }
        double overallMean = series.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (byMonth.size() >= 2 && overallMean != 0) {
            StringBuilder season = new StringBuilder("季节指数（>1 为旺季）：");
            for (Map.Entry<String, double[]> e : byMonth.entrySet()) {
                double meanM = e.getValue()[0] / e.getValue()[1];
                season.append(String.format(" %s月=%.2f", e.getKey(), meanM / overallMean));
            }
            sb.append(season).append('\n');
        }
        if (keys.size() < 13) sb.append("注意：不足 13 期，同比不可用（已标 —），季节指数参考性有限。");
        return sb.toString();
    }

    // ==================== 内部实现 ====================

    /**
     * 取本会话的分析对象（ToolContext 里带了 sessionId）。
     * 必须用 resolve：用户上传的文件是 pinned，AI 的探索性查询不能顶掉它。
     */
    private DataTable require(ToolContext ctx) {
        // 所有统计工具的第一道关：先扣一次工具调用预算（超了直接抛，让模型收尾）。
        // 放在这里而不是每个方法开头，是因为 10 个工具都必经这个方法。
        budget.guard("统计工具");
        String sid = (ctx == null || ctx.getContext() == null) ? null : (String) ctx.getContext().get("sessionId");
        DataTable t = session.resolve(sid);
        if (t == null) throw new IllegalStateException("当前会话还没有数据表，请先用 queryDatabase 取数或上传文件");
        return t;
    }

    /** 列名解析：先精确匹配，再忽略大小写/空格，容错 LLM 猜列名的情况 */
    private int resolve(DataTable t, String col) {
        int i = t.columns.indexOf(col);
        if (i >= 0) return i;
        String norm = col == null ? "" : col.trim().toLowerCase().replace("`", "").replace("\"", "");
        for (int k = 0; k < t.columns.size(); k++) {
            String c = t.columns.get(k);
            if (c != null && c.trim().toLowerCase().replace("_", "").equals(norm.replace("_", ""))) return k;
        }
        throw new IllegalArgumentException("找不到列：" + col + "，可用列：" + t.columns);
    }

    private double[] nums(DataTable t, String col) {
        return clean(t.columnAsDouble(resolve(t, col)));
    }

    private double[] clean(double[] a) {
        List<Double> list = new ArrayList<>();
        for (double d : a) if (!Double.isNaN(d)) list.add(d);
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    /** 数据质量：总行数 / 有效数 / 丢弃数 / 缺失率 —— 每个统计结论都必须带 */
    private Quality quality(int totalRows, double[] rawCol) {
        double[] vals = clean(rawCol);
        return new Quality(totalRows, vals.length, totalRows - vals.length, vals);
    }

    private record Quality(int total, int valid, int dropped, double[] values) {
        String line() {
            return String.format("[数据质量] 总行数=%d 有效=%d 丢弃=%d 缺失率=%.1f%%%s",
                    total, valid, dropped, total == 0 ? 0 : dropped * 100.0 / total,
                    valid < 30 ? " ⚠ 有效样本 <30，结论仅供参考" : "");
        }
    }

    /** 两列配对：只有两列都有值的行才进入计算（丢弃情况要报给模型） */
    private Pair pair(DataTable t, String colA, String colB) {
        int ia = resolve(t, colA), ib = resolve(t, colB);
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
        int dropped = 0;
        for (List<Object> row : t.rows) {
            Double x = toDouble(row.get(ia)), y = toDouble(row.get(ib));
            if (x == null || y == null) { dropped++; continue; }
            xs.add(x);
            ys.add(y);
        }
        return new Pair(toArr(xs), toArr(ys), dropped, t.rowCount());
    }

    /** 配对结果 + 数据质量（有效配对数、丢弃行数） */
    private record Pair(double[] a, double[] b, int dropped, int total) {
        String line() {
            return String.format("[数据质量] 总行数=%d 有效配对=%d 丢弃=%d%s",
                    total, a.length, dropped, a.length < 30 ? " ⚠ 有效样本 <30，结论仅供参考" : "");
        }
    }

    private double[] toArr(List<Double> l) {
        double[] out = new double[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
    }

    private Double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim().replace(",", "")); } catch (Exception e) { return null; }
        }
        return null;
    }

    private LocalDate toDate(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (v instanceof LocalDate d) return d;
        if (v instanceof LocalDateTime dt) return dt.toLocalDate();
        if (v instanceof Number n) {
            // Excel 序列号（1900 日期系统）
            double serial = n.doubleValue();
            if (serial > 20000 && serial < 80000) return LocalDate.of(1899, 12, 30).plusDays((long) serial);
            return null;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        for (DateTimeFormatter f : List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy/M/d"), DateTimeFormatter.ofPattern("yyyy-M-d"),
                DateTimeFormatter.ofPattern("yyyy.M.d"))) {
            try { return LocalDate.parse(s.length() > 19 ? s.substring(0, 19) : s, f); } catch (Exception ignored) { }
        }
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s, DateTimeFormatter.ISO_LOCAL_DATE); }
        catch (Exception ignored) { return null; }
    }

    /** 聚合：类目 → 求和，降序 */
    private Agg aggregate(DataTable t, String categoryCol, String valueCol) {
        int ci = resolve(t, categoryCol), vi = resolve(t, valueCol);
        Map<String, Double> map = new LinkedHashMap<>();
        for (List<Object> row : t.rows) {
            Object key = row.get(ci);
            Double v = toDouble(row.get(vi));
            if (key == null || v == null) continue;
            map.merge(String.valueOf(key), v, Double::sum);
        }
        List<Agg.Row> rows = new ArrayList<>();
        map.forEach((k, v) -> rows.add(new Agg.Row(k, v)));
        rows.sort((x, y) -> Double.compare(y.sum, x.sum));
        double total = rows.stream().mapToDouble(r -> r.sum).sum();
        return new Agg(rows, total);
    }

    private record Agg(List<Row> rows, double total) {
        boolean isEmpty() { return rows.isEmpty() || total == 0; }
        record Row(String key, double sum) { }
    }

    // ---------- 统计辅助 ----------

    private double var(double[] a) {
        DescriptiveStatistics s = new DescriptiveStatistics();
        for (double d : a) s.addValue(d);
        return s.getVariance();
    }

    private double maxVarRatio(double[] a, double[] b) {
        double va = var(a), vb = var(b);
        if (va == 0 || vb == 0) return Double.NaN;
        return va > vb ? va / vb : vb / va;
    }

    private double safeCv(double mean, double sd) {
        return mean == 0 ? Double.NaN : sd / Math.abs(mean);
    }

    /** 相关系数的 p 值（t 近似） */
    private String pvalueOfCorrelation(double r, int n) {
        if (n <= 2 || Math.abs(r) >= 1) return "样本量/数值不足以给出 p 值";
        double t = r * Math.sqrt((n - 2) / (1 - r * r));
        double p = 2 * (1 - new TDistribution(n - 2).cumulativeProbability(Math.abs(t)));
        return String.format("n=%d p=%.4f（%s）", n, p, p < 0.05 ? "显著不为 0" : "不显著");
    }

    private static double fisherZ(double r) { return 0.5 * Math.log((1 + r) / (1 - r)); }

    private double ciLow(double r, int n) {
        if (n <= 3 || Math.abs(r) >= 1) return Double.NaN;
        return Math.tanh(fisherZ(r) - 1.96 / Math.sqrt(n - 3));
    }

    private double ciHigh(double r, int n) {
        if (n <= 3 || Math.abs(r) >= 1) return Double.NaN;
        return Math.tanh(fisherZ(r) + 1.96 / Math.sqrt(n - 3));
    }

    /** 正态性参考：KS 检验 + 偏度峰度经验判断（参数由样本估计，属参考值不是严格检验） */
    private String normality(double[] v, double mean, double sd) {
        if (v.length < 8) return "正态性：样本 <8，不判断（小样本请谨慎使用均值类方法）";
        StringBuilder sb = new StringBuilder("正态性参考：");
        try {
            double p = new KolmogorovSmirnovTest().kolmogorovSmirnovTest(new NormalDistribution(mean, sd), v);
            sb.append(String.format("KS p=%.4f（%s；参数由样本估计，仅供参考）", p,
                    p > 0.05 ? "不能拒绝正态假设" : "偏离正态明显"));
        } catch (Exception e) {
            sb.append("未能计算");
        }
        DescriptiveStatistics s = new DescriptiveStatistics();
        for (double d : v) s.addValue(d);
        sb.append(String.format("，偏度=%.2f 峰度=%.2f（越接近 0 越像正态）", s.getSkewness(), s.getKurtosis()));
        return sb.toString();
    }

    private String effect(double d) {
        if (Double.isNaN(d)) return "无法计算";
        double x = Math.abs(d);
        if (x < 0.2) return "效应极小";
        if (x < 0.5) return "效应小";
        if (x < 0.8) return "效应中等";
        return "效应大";
    }

    private String pct(double cur, double prev) {
        if (prev == 0) return "—";
        return String.format("%+.1f%%", (cur - prev) / Math.abs(prev) * 100);
    }

    private double movingAverage(List<Double> s, int i, int k) {
        int from = Math.max(0, i - k + 1);
        double sum = 0, n = 0;
        for (int j = from; j <= i; j++) { sum += s.get(j); n++; }
        return n == 0 ? 0 : sum / n;
    }

    private double minExpected(long[][] counts, long total) {
        long[] rowSum = new long[counts.length], colSum = new long[counts[0].length];
        for (int i = 0; i < counts.length; i++)
            for (int j = 0; j < counts[0].length; j++) { rowSum[i] += counts[i][j]; colSum[j] += counts[i][j]; }
        double min = Double.MAX_VALUE;
        for (int i = 0; i < counts.length; i++)
            for (int j = 0; j < counts[0].length; j++) {
                double e = (rowSum[i] * (double) colSum[j]) / total;
                min = Math.min(min, e);
            }
        return min;
    }

    private double share(double part, double total) {
        return total == 0 ? 0 : part / total * 100;
    }

}
