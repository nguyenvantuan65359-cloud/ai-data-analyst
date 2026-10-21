package com.example.analyst;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 发现引擎：从「你问它答」变成「主动告诉你哪里不对」。
 *
 * 判据四要素，缺一不可（少一条就会满屏假异常）：
 *   偏离基线（环比 / 3 期累计 / 横截面期望）
 * × 超过噪声（中位数+MAD 的稳健 z，不用均值+标准差——一个异常点就会污染基线，然后它自己不再是异常）
 * × 有业务体量（贡献占整体变化 ≥1%，或偏离贡献 ≥3%）
 * × 样本够（比率类指标分母 ≥15，横截面 n ≥8）
 *
 * **全程不调模型**：扫描几千个切片是毫秒级的事，交给 SQL/Java 做；
 * 模型只在最后解读 Top N。反过来做（让模型逐个看切片）会同时烧光和上下文。
 */
@Component
public class DiscoveryEngine {

    /** 比率类指标的最小分母：订单只有 11 单时，单价可以从 100 跳到 500，那是噪声不是发现 */
    private static final int MIN_N_RATIO = 15;
    /** 横截面判定的最小样本 */
    private static final int MIN_N_CROSS = 8;
    /** 体量门槛：对整体变化的贡献低于这个比例，不值得一提 */
    private static final double MIN_SHARE_PCT = 1.0;
    /** 横截面偏离占总量门槛 */
    private static final double MIN_CROSS_SHARE_PCT = 3.0;
    /** 横截面偏离门槛：O/E 至少要差 25%，否则就是同类内的正常波动 */
    private static final double MIN_OE_DEVIATION = 0.25;
    /** 切片数上限，防止交叉维度爆炸 */
    private static final int MAX_SLICES = 800;
    private static final int TOP_N = 8;

    public ScanReport scan(DataTable t) {
        long t0 = System.currentTimeMillis();
        ScanReport rep = new ScanReport();
        if (t == null || t.rowCount() == 0) {
            rep.mode = "N";
            rep.modeLabel = "无法分析";
            rep.headline = "没有可分析的数据";
            return rep;
        }

        SchemaSkeleton sk = SchemaSkeleton.detect(t);
        rep.measures.addAll(sk.measures);
        rep.dims.addAll(sk.dims);
        rep.notes.addAll(sk.notes);
        rep.timeCol = sk.timeCol;
        rep.periodCount = sk.periodCount;
        rep.quality.addAll(qualityNotes(t, sk));

        boolean timeMode = sk.timeCol != null && sk.periodCount >= SchemaSkeleton.MIN_PERIODS;
        String primary = sk.amountCol != null ? sk.amountCol
                : (sk.measures.isEmpty() ? null : sk.measures.get(0));

        if (primary == null) {
            rep.mode = "N";
            rep.modeLabel = "无法分析";
            rep.headline = "没有识别出可度量的数值列（找不到能求和/求比的指标）";
            rep.elapsedMs = System.currentTimeMillis() - t0;
            return rep;
        }

        if (timeMode) {
            runTimeMode(t, sk, primary, rep);
        } else {
            rep.mode = "B";
            rep.modeLabel = "横截面模式（不判断涨跌）";
            runCrossMode(t, sk, primary, rep);
        }
        rep.elapsedMs = System.currentTimeMillis() - t0;
        return rep;
    }

    // ==================== 模式 A：时间模式 ====================

    private void runTimeMode(DataTable t, SchemaSkeleton sk, String primary, ScanReport rep) {
        rep.mode = "A";
        rep.modeLabel = "时间模式（可判断趋势与异常）";
        rep.metric = primary;

        // 维度组合：所有单维 + 前两个维度的交叉。交叉是发现"地区好、品类好、组合崩"的唯一途径
        List<List<String>> combos = new ArrayList<>();
        for (String d : sk.dims) combos.add(List.of(d));
        if (sk.dims.size() >= 2) combos.add(List.of(sk.dims.get(0), sk.dims.get(1)));

        List<ScanReport.Finding> all = new ArrayList<>();
        int scanned = 0;

        for (List<String> combo : combos) {
            // 主指标跑全部组合
            Agg a = aggregate(t, sk, combo, primary, false);
            if (a == null) continue;
            scanned += a.slices;
            if (rep.periods.isEmpty()) fillPeriods(rep, a);
            all.addAll(judgeTime(a, combo, primary, rep, false));

            // 数量与单价只跑单维（交叉再乘一遍只会重复报同一个现象）
            if (combo.size() == 1) {
                if (sk.qtyCol != null && !sk.qtyCol.equals(primary)) {
                    Agg q = aggregate(t, sk, combo, sk.qtyCol, false);
                    if (q != null) {
                        scanned += q.slices;
                        all.addAll(judgeTime(q, combo, sk.qtyCol, rep, false));
                    }
                }
                if (sk.priceLabel != null) {
                    Agg p = aggregate(t, sk, combo, primary, true);
                    if (p != null) {
                        scanned += p.slices;
                        all.addAll(judgeTime(p, combo, sk.priceLabel, rep, true));
                    }
                }
            }
        }

        rep.slicesScanned = scanned;
        rep.totalCandidates = all.size();
        all.sort((x, y) -> Double.compare(weight(y), weight(x)));
        rep.findings = all.size() > TOP_N ? new ArrayList<>(all.subList(0, TOP_N)) : all;

        // 反向检验：只用我们自己的数据，不问外部
        for (ScanReport.Finding f : rep.findings) {
            f.peer = peerCheck(f, all);
        }
        rep.headline = timeHeadline(rep, all);
    }

    private static void fillPeriods(ScanReport rep, Agg a) {
        rep.periods = new ArrayList<>(a.periodLabels);
        rep.overallSeries = new ArrayList<>();
        for (int i = 0; i < a.periods.size(); i++) {
            double v = 0;
            for (double[] arr : a.values.values()) v += arr[i];
            rep.overallSeries.add(v);
        }
        int last = a.periods.size() - 1;
        rep.totalDelta = rep.overallSeries.get(last) - rep.overallSeries.get(last - 1);
        rep.growth = rep.overallSeries.get(last - 1) == 0 ? 0
                : rep.totalDelta / rep.overallSeries.get(last - 1);
    }

    /**
     * 逐切片判定。三种信号各自有意义，不能只留一种：
     *   单期超额  —— 这个月突然变了
     *   3 期超额  —— 连续三期慢慢掉（真实业务里最常见，也最容易被单期口径漏掉）
     *   稳健 z    —— 相对自身历史波动是否离谱
     */
    private List<ScanReport.Finding> judgeTime(Agg a, List<String> combo, String metric,
                                               ScanReport rep, boolean ratio) {
        List<ScanReport.Finding> out = new ArrayList<>();
        int last = a.periods.size() - 1;
        int prev = last - 1;
        if (prev < 1) return out;

        // 整体基准（比率指标必须 SUM(分子)/SUM(分母)，不能把行级比值相加）
        double oLast = a.overall(last), oPrev = a.overall(prev);
        double growth = oPrev == 0 ? 0 : (oLast - oPrev) / oPrev;
        double totalDelta = oLast - oPrev;
        double g3 = triGrowth(a);

        for (Map.Entry<String, double[]> e : a.values.entrySet()) {
            double[] s = e.getValue();
            double vLast = s[last], vPrev = s[prev];
            if (vPrev == 0 && vLast == 0) continue;

            double delta = vLast - vPrev;
            double excess = delta - vPrev * growth;

            // 3 期对 3 期
            Double exc3 = null;
            if (s.length >= 6) {
                double cur = s[last] + s[last - 1] + s[last - 2];
                double base = s[last - 3] + s[last - 4] + s[last - 5];
                if (base != 0) exc3 = (cur - base) - base * g3;
            }

            double z = madZ(s, vLast);
            double share = totalDelta == 0 ? 0 : delta / Math.abs(totalDelta) * 100;
            int n = a.count(e.getKey());

            ScanReport.Finding f = new ScanReport.Finding();
            f.slice = e.getKey();
            f.metric = metric;
            f.dim = String.join("×", combo);
            f.last = round2(vLast);
            f.prev = round2(vPrev);
            f.delta = round2(delta);
            f.pct = vPrev == 0 ? 0 : round2(delta / vPrev * 100);
            f.excess = round2(excess);
            f.excess3 = exc3 == null ? 0 : round2(exc3);
            f.z = z == 0 ? 0 : round2(z);
            f.sharePct = round2(share);
            f.n = n;
            f.evidence = evidenceSql(combo, metric, true);

            // 闸门一：比率类指标的分母失真 → 不判定（这条拦住的是"订单 11 单导致单价 +426%"那种假异常）
            String block = blockReason(a, e.getKey(), last, ratio, delta, vPrev);
            if (block != null) {
                f.kind = "已拦下";
                f.why = block;
                f.strength = "-";
                rep.blocked.add(f);
                continue;
            }

            // 闸门二：体量门槛。贡献不到整体 1% 的切片，报上去只会淹没真信号
            if (Math.abs(share) < MIN_SHARE_PCT) continue;

            // 主信号：3 期累计 vs 最近一期，取量级更大的那个来说话。
            // 不这么做就会出现"3 期在涨、最近一期在跌"被写成单边结论的情况。
            boolean useCum = exc3 != null && Math.abs(exc3) >= Math.abs(excess * 3);
            double main = useCum ? exc3 : excess * 3;

            // 闸门三：平稳过滤。三条判据一条都没碰到 = 这条切片近期没什么可说的。
            // 不加这一步，候选数会等于切片总数，判据就只排序不筛选，等于没判。
            double scale = Math.abs(vLast) < 1e-9 ? 1 : Math.abs(vLast);
            boolean flat = Math.abs(f.pct) < 5 && Math.abs(z) < 1.5 && Math.abs(main) < 0.1 * scale;
            if (flat) continue;

            f.kind = (main >= 0 ? "增长" : "下滑") + (useCum ? "（3 期累计）" : "（最近一期）");

            // 样本可信度影响排名，而不只是写进提醒。
            // 交叉切片每格每期不到 2 行时，环比就是"单行对单行"，波动主要来自单笔大小。
            double per = n / (double) Math.max(1, a.periods.size());
            if (combo.size() >= 2 && per < 2) f.reliability = 0.5;

            f.strength = strength(z, share, exc3);
            if (f.reliability < 1 && "强".equals(f.strength)) f.strength = "中";
            f.why = whyText(f, exc3);
            out.add(f);
        }
        return dedupe(out);
    }

    private static String blockReason(Agg a, String key, int last, boolean ratio, double delta, double vPrev) {
        if (!ratio) return null;
        double[] q = a.denom.get(key);
        if (q == null) return null;
        int qLast = (int) Math.round(q[last]);
        int qPrev = last >= 1 ? (int) Math.round(q[last - 1]) : 0;
        if (qLast < MIN_N_RATIO) return "分母过小（" + qLast + " < " + MIN_N_RATIO + "），比率不可靠";
        List<Integer> hist = new ArrayList<>();
        for (int i = 0; i < last; i++) if (q[i] > 0) hist.add((int) Math.round(q[i]));
        int qMed = hist.isEmpty() ? 0 : medianInt(hist);
        if (qMed > 0 && qLast < 0.3 * qMed) {
            return "分母骤降至 " + qLast + "（常期约 " + qMed + "），指标失真";
        }
        if (qPrev > 0) {
            double qChg = (qLast - qPrev) / (double) qPrev;
            if (Math.abs(qChg) > 0.7 && delta * qChg < 0) {
                return String.format("量价严重背离（量 %+.0f%% / 金额 %+.0f%%），多为数据断裂或极端单笔",
                        qChg * 100, vPrev == 0 ? 0 : delta / vPrev * 100);
            }
        }
        return null;
    }

    /** 强度由判据命中条数决定，不是模型的感觉 */
    private static String strength(double z, double share, Double exc3) {
        int hits = 0;
        if (Math.abs(z) >= 2) hits++;
        if (Math.abs(share) >= 3) hits++;
        if (exc3 != null && Math.abs(exc3) > 0) hits++;
        return hits >= 3 ? "强" : (hits >= 2 ? "中" : "弱");
    }

    private static String whyText(ScanReport.Finding f, Double exc3) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("环比 %+.1f%%（%s → %s）", f.pct, fmt(f.prev), fmt(f.last)));
        if (exc3 != null) {
            sb.append(String.format("；3 期累计超额 %s（剥离大盘增速后的净变化）", fmt(exc3)));
            // 方向相反 = 拐点刚发生。这是最该被看见的一条，但只报数字读者看不出来
            if (Math.signum(exc3) != Math.signum(f.excess)) {
                sb.append(exc3 > 0
                        ? "。**注意：3 期累计仍在涨，但最近一期已经掉头，拐点就在最近两期**"
                        : "。**注意：3 期累计在跌，但最近一期已经回升，可能已经见底**");
            }
        }
        sb.append(String.format("；稳健 z = %.2f（中位数/MAD，抗单点污染）", f.z));
        sb.append(String.format("；对整体变化的贡献 %+.1f%%", f.sharePct));
        if (f.reliability < 1) {
            sb.append("；**交叉切片每期样本不足 2 行，波动主要来自单笔大小，只作线索不作结论**");
        } else if (f.n < 30) {
            sb.append("；样本 n=").append(f.n).append("，偏小，方向可信但幅度需谨慎");
        }
        return sb.toString();
    }

    /**
     * 排序权重 = 变化量级 × 异常程度加成。
     * 只用 |超额| 排序，会让"稳定增长的大盘子"压过"突然暴跌的小盘子"——
     * 但用户更该先看后者。所以叠加 |z| 最多 2 倍的加成。
     */
    private static double weight(ScanReport.Finding f) {
        double mag = Math.max(Math.abs(f.excess3), Math.abs(f.excess * 3));
        double boost = 1 + Math.min(Math.abs(f.z), 3) / 3;
        return mag * boost * f.reliability;
    }

    /** 同一现象被单维和交叉各报一次时，只留量级更大的那条 */
    private static List<ScanReport.Finding> dedupe(List<ScanReport.Finding> in) {
        List<ScanReport.Finding> out = new ArrayList<>();
        for (ScanReport.Finding f : in) {
            boolean dup = false;
            for (ScanReport.Finding kept : out) {
                if (!kept.metric.equals(f.metric)) continue;
                if (!kept.dim.equals(f.dim)) continue;
                if (kept.slice.equals(f.slice)) { dup = true; break; }
            }
            if (!dup) out.add(f);
        }
        return out;
    }

    // ==================== 反向检验 ====================

    /**
     * 同期同类切片是否同向变化。**这是三件校验里最硬的一条，且只用自己的数据。**
     * 全变 → 品类/行业层面共性；只有它变 → 更可能与本切片自身因素有关（价格、流量、库存、活动漏报）。
     * 注意措辞：只能说"更可能"，不能断言是内部问题——上游断供这类因素不在数据里。
     */
    private static String peerCheck(ScanReport.Finding f, List<ScanReport.Finding> all) {
        String[] parts = f.slice.split("×");
        if (parts.length < 2) {
            // 单维：看同维度其他切片是否同向
            List<String> same = new ArrayList<>();
            int sameDir = 0, total = 0;
            for (ScanReport.Finding o : all) {
                if (!o.dim.equals(f.dim) || o.slice.equals(f.slice)) continue;
                total++;
                if (Math.signum(o.delta) == Math.signum(f.delta)) sameDir++;
                if (same.size() < 3) same.add(o.slice + " " + pct(o.pct));
            }
            if (total == 0) return "无同类切片可比";
            String list = String.join("、", same);
            if (sameDir == total) {
                return "同维度 " + total + " 个切片全部同向（" + list + "）→ 更像整体共性，不是单点问题";
            }
            return "同维度 " + sameDir + "/" + total + " 个切片同向（" + list + "）→ 存在分化，需再看细化切片";
        }
        // 二维：分别看"同第一维""同第二维"的其他切片
        List<String> opposite = new ArrayList<>();
        int sameDir = 0, total = 0;
        for (ScanReport.Finding o : all) {
            if (!o.dim.equals(f.dim) || o.slice.equals(f.slice)) continue;
            String[] op = o.slice.split("×");
            if (op.length != parts.length) continue;
            boolean shareOne = op[0].equals(parts[0]) || op[1].equals(parts[1]);
            if (!shareOne) continue;
            total++;
            if (Math.signum(o.delta) == Math.signum(f.delta)) sameDir++;
            else if (opposite.size() < 3) opposite.add(o.slice + " " + pct(o.pct));
        }
        if (total == 0) return "无共享维度的切片可比，暂不做反向检验";
        if (!opposite.isEmpty()) {
            return "同维度其他切片方向相反（" + String.join("、", opposite)
                    + "）→ 品类与地区本身都没坏，更可能是这个组合自身的问题（供货/库存/价格/活动漏报），建议先查这一路";
        }
        if (sameDir == total) {
            return "同维度 " + total + " 个切片全部同向 → 层级共性，不像单点异常";
        }
        return "同维度 " + sameDir + "/" + total + " 个切片同向，存在分化";
    }

    // ==================== 模式 B：横截面 ====================

    private void runCrossMode(DataTable t, SchemaSkeleton sk, String primary, ScanReport rep) {
        rep.metric = primary;
        rep.headline = "没有可用时间轴：只能判断「谁相对同类偏高/偏低」，**不能判断涨跌**";

        List<List<String>> combos = new ArrayList<>();
        for (String d : sk.dims) combos.add(List.of(d));
        if (sk.dims.size() >= 2) combos.add(List.of(sk.dims.get(0), sk.dims.get(1)));

        double total = 0;
        for (int r = 0; r < t.rowCount(); r++) {
            Double v = SchemaSkeleton.toNum(t.get(r, t.columns.indexOf(primary)));
            if (v != null) total += v;
        }
        if (total == 0) {
            rep.findings = List.of();
            return;
        }

        List<ScanReport.Finding> all = new ArrayList<>();
        int scanned = 0;
        for (List<String> combo : combos) {
            // 期望值按父级分组算：同父维度内比较，否则大切片必然"偏高"
            int groupBy = combo.size() >= 2 ? 0 : -1;
            Map<String, double[]> agg = new LinkedHashMap<>();   // key -> [sum, n]
            Map<String, String> parent = new HashMap<>();
            for (int r = 0; r < t.rowCount(); r++) {
                Double v = SchemaSkeleton.toNum(t.get(r, t.columns.indexOf(primary)));
                if (v == null) continue;
                List<String> parts = new ArrayList<>();
                for (String d : combo) parts.add(SchemaSkeleton.stringify(t.get(r, t.columns.indexOf(d))));
                String key = String.join("×", parts);
                double[] cell = agg.computeIfAbsent(key, k -> new double[2]);
                cell[0] += v;
                cell[1] += 1;
                if (groupBy >= 0) parent.put(key, parts.get(groupBy));
            }
            scanned += agg.size();

            // 父级总盘 + 父级行数。期望必须用「父组内行数占比」，
            // 用全表占比会把每个切片的期望都算小 4 倍（实测 z 跑到 465）。
            Map<String, Double> parentTotal = new HashMap<>();
            Map<String, Double> parentRows = new HashMap<>();
            for (Map.Entry<String, double[]> e : agg.entrySet()) {
                String p = groupBy >= 0 ? parent.get(e.getKey()) : "_";
                parentTotal.merge(p, e.getValue()[0], Double::sum);
                parentRows.merge(p, e.getValue()[1], Double::sum);
            }

            for (Map.Entry<String, double[]> e : agg.entrySet()) {
                double v = e.getValue()[0];
                int n = (int) e.getValue()[1];
                String g = groupBy >= 0 ? parent.get(e.getKey()) : "_";
                double gt = parentTotal.getOrDefault(g, total);
                double rows = parentRows.getOrDefault(g, (double) t.rowCount());
                // 组内期望 = 组内总盘 × 该切片在组内的样本占比
                double exp = rows <= 0 ? 0 : gt * n / rows;
                if (exp <= 0) continue;

                ScanReport.Finding f = new ScanReport.Finding();
                f.slice = e.getKey();
                f.metric = primary;
                f.dim = String.join("×", combo);
                f.last = round2(v);
                f.prev = round2(exp);
                f.delta = round2(v - exp);
                f.pct = round2((v / exp - 1) * 100);
                f.oe = round2(v / exp);
                f.sharePct = round2((v - exp) / total * 100);
                f.n = n;
                f.evidence = evidenceSql(combo, primary, false);

                if (n < MIN_N_CROSS) {
                    f.kind = "已拦下";
                    f.strength = "-";
                    f.why = "样本 n=" + n + " < " + MIN_N_CROSS + "，不判定（小样本的偏离几乎全是噪声）";
                    rep.blocked.add(f);
                    continue;
                }
                // 金额是连续量、方差远大于泊松，用 (v-exp)/sqrt(exp) 会算出 z=465 这种荒谬值。
                // 横截面模式只主张"相对同类偏离"，所以判据用 O/E 比，不做显著性宣称。
                if (Math.abs(f.oe - 1) < MIN_OE_DEVIATION) continue;
                if (Math.abs(f.sharePct) < MIN_CROSS_SHARE_PCT) continue;

                f.kind = v > exp ? "效率偏高" : "效率偏低";
                f.strength = Math.abs(f.oe - 1) >= 0.5 && Math.abs(f.sharePct) >= 6 ? "强" : "中";
                f.why = String.format("实际 %s vs 期望 %s（按同组样本量标准化），O/E = %.2f，"
                        + "偏离贡献占总量 %+.1f%%。**这是变异系数比较，不是显著性检验**",
                        fmt(v), fmt(exp), f.oe, f.sharePct);
                f.peer = "横截面模式无时间基线，反向检验不可用；此处只能说「相对同类偏离」，不能读作涨跌";
                all.add(f);
            }
        }

        rep.slicesScanned = scanned;
        rep.totalCandidates = all.size();
        all.sort((x, y) -> Double.compare(Math.abs(y.oe - 1) * Math.abs(y.sharePct),
                                         Math.abs(x.oe - 1) * Math.abs(x.sharePct)));
        rep.findings = all.size() > TOP_N ? new ArrayList<>(all.subList(0, TOP_N)) : all;

        if (!all.isEmpty()) {
            ScanReport.Finding top = all.get(0);
            rep.headline = "相对同类偏离最大的是「" + top.slice + "」：实际 " + fmt(top.last)
                    + "，期望 " + fmt(top.prev) + "，O/E = " + String.format("%.2f", top.last / top.prev)
                    + "。**这是横截面偏离，不是增长或下滑。**";
        } else if (!rep.blocked.isEmpty()) {
            ScanReport.Finding b = rep.blocked.get(0);
            rep.headline = "没有任何切片达到判定条件，其中 " + rep.blocked.size()
                    + " 个因样本不足被拦（" + b.slice + " n=" + b.n + "）。"
                    + "**不是数据没问题，是这份数据还不足以判断**——"
                    + "要么换更粗的维度，要么把时间窗拉长。";
        } else {
            rep.headline = "所有切片都落在同类期望的 ±" + MIN_OE_DEVIATION
                    + " 倍噪声范围内，横截面上是均衡的，没有值得单独指出的切片。"
                    + "注意：这只说明「没有谁特别偏高/偏低」，不代表业务没问题。";
        }
    }

    // ==================== 聚合 ====================

    private static class Agg {
        List<Integer> periods = new ArrayList<>();
        List<String> periodLabels = new ArrayList<>();
        /** 分子原值（求和），比率指标也不能被覆盖——整体基准要用它 */
        Map<String, double[]> raw = new LinkedHashMap<>();
        /** 展示值：普通指标=求和，比率指标=分子/分母 */
        Map<String, double[]> values = new LinkedHashMap<>();
        /** 分母：比率指标=数量合计，普通指标=行数 */
        Map<String, double[]> denom = new LinkedHashMap<>();
        int slices;
        boolean ratio;

        int count(String key) {
            double[] d = denom.get(key);
            if (d == null) return 0;
            int s = 0;
            for (double v : d) s += (int) Math.round(v);
            return s;
        }

        /** 整体基准。比率指标必须 SUM(分子)/SUM(分母)，不能对行级比值求平均 */
        double overall(int idx) {
            if (ratio) {
                double num = 0, den = 0;
                for (double[] arr : raw.values()) num += arr[idx];
                for (double[] arr : denom.values()) den += arr[idx];
                return den == 0 ? 0 : num / den;
            }
            double num = 0;
            for (double[] arr : values.values()) num += arr[idx];
            return num;
        }
    }

    /**
     * 按维度组合聚合成「切片 × 期」矩阵。
     * ratio=true 时按 SUM(分子)/SUM(分母) 计算比值——**不能把行级单价相加**，
     * 那是第一版犯过的错，算出来的"整体单价"毫无意义。
     */
    private Agg aggregate(DataTable t, SchemaSkeleton sk, List<String> combo, String metric, boolean ratio) {
        Agg a = new Agg();
        a.ratio = ratio;
        int timeIdx = t.columns.indexOf(sk.timeCol);
        int numIdx = t.columns.indexOf(metric);
        int denIdx = ratio ? t.columns.indexOf(sk.qtyCol) : -1;
        if (numIdx < 0 || timeIdx < 0) return null;

        TreeMap<Integer, String> per = new TreeMap<>();
        for (int r = 0; r < t.rowCount(); r++) {
            Integer p = SchemaSkeleton.parsePeriod(t.get(r, timeIdx));
            if (p != null) per.putIfAbsent(p, SchemaSkeleton.stringify(t.get(r, timeIdx)));
        }
        if (per.size() < 3) return null;

        a.periods = new ArrayList<>(per.keySet());
        a.periodLabels = new ArrayList<>(per.values());
        Map<Integer, Integer> idx = new HashMap<>();
        for (int i = 0; i < a.periods.size(); i++) idx.put(a.periods.get(i), i);

        int np = a.periods.size();
        for (int r = 0; r < t.rowCount(); r++) {
            Integer p = SchemaSkeleton.parsePeriod(t.get(r, timeIdx));
            if (p == null) continue;
            Double v = SchemaSkeleton.toNum(t.get(r, numIdx));
            if (v == null) continue;

            List<String> parts = new ArrayList<>();
            for (String d : combo) parts.add(SchemaSkeleton.stringify(t.get(r, t.columns.indexOf(d))));
            String key = String.join("×", parts);
            int pi = idx.get(p);

            double[] raw = a.raw.computeIfAbsent(key, k -> new double[np]);
            double[] den = a.denom.computeIfAbsent(key, k -> new double[np]);
            raw[pi] += v;

            if (ratio) {
                Double q = denIdx >= 0 ? SchemaSkeleton.toNum(t.get(r, denIdx)) : null;
                den[pi] += q == null ? 0 : q;
            } else {
                den[pi] += 1;   // 普通指标的"分母"就是行数，横截面判定要用
            }
        }
        if (a.raw.size() > MAX_SLICES) return null;
        a.slices = a.raw.size();

        for (String k : a.raw.keySet()) {
            double[] raw = a.raw.get(k);
            double[] den = a.denom.get(k);
            double[] show = new double[np];
            for (int i = 0; i < np; i++) {
                show[i] = (ratio && den[i] > 0) ? raw[i] / den[i] : raw[i];
            }
            a.values.put(k, show);
        }
        return a;
    }

    // ==================== 小工具 ====================

    private static double triGrowth(Agg a) {
        int n = a.periods.size();
        if (n < 6) return 0;
        double cur = 0, base = 0;
        for (int i = n - 3; i < n; i++) cur += a.overall(i);
        for (int i = n - 6; i < n - 3; i++) base += a.overall(i);
        return base == 0 ? 0 : (cur - base) / base;
    }

    /** 稳健 z：中位数 + MAD。均值/标准差会被那个异常点自己污染 */
    private static double madZ(double[] s, double x) {
        if (s.length < 3) return 0;
        double[] copy = s.clone();
        Arrays.sort(copy);
        double med = median(copy);
        double[] dev = new double[copy.length];
        for (int i = 0; i < copy.length; i++) dev[i] = Math.abs(copy[i] - med);
        Arrays.sort(dev);
        double mad = median(dev);
        if (mad == 0) return 0;
        return (x - med) / (1.4826 * mad);
    }

    private static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    private static int medianInt(List<Integer> in) {
        List<Integer> s = new ArrayList<>(in);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }

    private static String pct(double v) {
        return String.format("%+.1f%%", v);
    }

    private static String fmt(double v) {
        if (Math.abs(v) >= 10000) return String.format("%,.0f", v);
        if (Math.abs(v) >= 100) return String.format("%,.1f", v);
        return String.format("%.2f", v);
    }

    private static double round2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return Math.round(v * 100.0) / 100.0;
    }

    private static String evidenceSql(List<String> combo, String metric, boolean timeMode) {
        String dims = String.join(", ", combo);
        if (timeMode) {
            return "SELECT " + dims + ", 时间列, SUM(" + metric + ") FROM uploaded_data GROUP BY "
                    + dims + ", 时间列 ORDER BY 时间列";
        }
        return "SELECT " + dims + ", SUM(" + metric + ") AS V, COUNT(*) AS N FROM uploaded_data GROUP BY " + dims;
    }

    private static String timeHeadline(ScanReport rep, List<ScanReport.Finding> all) {
        if (all.isEmpty()) {
            return "扫描了 " + rep.slicesScanned + " 个切片，没有一个同时满足「偏离基线 + 超过噪声 + 有业务体量」，"
                    + "这份数据在最近一期是平稳的";
        }
        ScanReport.Finding top = all.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("整体环比 %+.1f%%（Δ=%s）。最值得看的是「%s」（%s）：%s %+.1f%%，"
                        + "剥离大盘增速后仍有 %s 的净变化。",
                rep.growth * 100, fmt(rep.totalDelta), top.slice, top.metric, top.kind, top.pct, fmt(top.excess3 != 0 ? top.excess3 : top.excess)));
        long down = all.stream().filter(x -> x.excess3 < 0 || (x.excess3 == 0 && x.delta < 0)).count();
        sb.append(String.format("共 %d 条候选（已按「量级 × 异常程度」排序，此处展示前 %d 条），其中 %d 条走低、%d 条走高。",
                all.size(), rep.findings.size(), down, all.size() - down));
        return sb.toString();
    }

    // ==================== 数据质量 ====================

    private static List<String> qualityNotes(DataTable t, SchemaSkeleton sk) {
        List<String> q = new ArrayList<>();
        if (t.rowCount() < 20) {
            q.add("只有 " + t.rowCount() + " 行数据，任何结论都容易受单行影响");
        }
        // 空值率
        for (int c = 0; c < t.colCount(); c++) {
            int blank = 0;
            for (int r = 0; r < t.rowCount(); r++) {
                if (SchemaSkeleton.stringify(t.get(r, c)).isEmpty()) blank++;
            }
            double rate = blank * 100.0 / t.rowCount();
            if (rate >= 5) {
                q.add("列「" + t.columns.get(c) + "」空值率 " + String.format("%.1f%%", rate)
                        + "（" + blank + "/" + t.rowCount() + "），求和类指标会被低估");
            }
        }
        // 完全重复行
        Set<String> seen = new HashSet<>();
        int dup = 0;
        for (int r = 0; r < t.rowCount(); r++) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < t.colCount(); c++) sb.append(SchemaSkeleton.stringify(t.get(r, c))).append('\u0001');
            if (!seen.add(sb.toString())) dup++;
        }
        if (dup > 0) {
            q.add("存在 " + dup + " 行完全重复，可能是导出重复或双向流水，直接求和不准确");
        }
        // 交叉粒度过细：每个格子每期不到 2 行时，环比就是单行对单行的比较
        if (sk.dims.size() >= 2 && sk.timeCol != null && sk.periodCount > 0) {
            int cells = 1;
            for (int i = 0; i < Math.min(2, sk.dims.size()); i++) {
                cells *= Math.max(1, SchemaSkeleton.distinctCount(t, t.columns.indexOf(sk.dims.get(i))));
            }
            double per = t.rowCount() / (double) Math.max(1, cells * sk.periodCount);
            if (per < 2) {
                q.add(String.format("交叉粒度到「%s × %s」时，每格每期只有 %.1f 行——"
                        + "组合结论的波动主要来自单笔大小，不是趋势", sk.dims.get(0), sk.dims.get(1), per));
            }
        }
        // 疑似主键维度
        for (String d : sk.dims) {
            int idx = t.columns.indexOf(d);
            if (SchemaSkeleton.distinctCount(t, idx) == t.rowCount() && t.rowCount() > 10) {
                q.add("列「" + d + "」每行都不同，像主键，不适合当分析维度");
            }
        }
        return q;
    }
}
