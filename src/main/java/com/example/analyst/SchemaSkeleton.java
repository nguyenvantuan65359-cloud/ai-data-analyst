package com.example.analyst;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据骨架识别：哪一列是时间轴、哪些是度量、哪些是维度。
 *
 * 全部靠**结构**判断，列名只用来打破平局。真实业务表表头五花八门
 * （"销售额"/"GMV"/"2026-09"/"26W38"/"账期"），靠列名必漏。
 *
 * 时间识别踩过的坑：只按"取值是小整数"判断，会把 qty（订单数）认成时间轴，
 * 于是整个分析跑在错的轴上——输出看着齐全，其实全错。所以要三重约束。
 */
public class SchemaSkeleton {

    /** 时间模式的最少期数：少于这个数，序列没有"常态"可言，不能判趋势 */
    public static final int MIN_PERIODS = 5;

    public String timeCol;
    public int periodCount;
    public final List<String> timeCandidates = new ArrayList<>();
    public final List<String> measures = new ArrayList<>();
    public final List<String> dims = new ArrayList<>();
    public String amountCol;
    public String qtyCol;
    public String priceLabel;
    public final List<String> notes = new ArrayList<>();

    private static final String[] TIME_HINTS = {
            "month", "月", "period", "期", "year", "年", "week", "周", "date", "日期", "day", "日", "账期"
    };
    private static final String[] AMOUNT_HINTS = {
            "amount", "金额", "sales", "revenue", "gmv", "销售额", "收入", "营收", "营业额"
    };
    private static final String[] QTY_HINTS = {
            "order", "qty", "quantity", "数量", "单量", "销量", "件数", "订单"
    };

    // ================= 时间解构 =================

    private static final Pattern P_YM6 = Pattern.compile("((?:19|20)\\d{2})(0[1-9]|1[0-2])");
    private static final Pattern P_YM = Pattern.compile("((?:19|20)\\d{2})\\s*[-/年.]?\\s*(\\d{1,2})\\s*月?");
    private static final Pattern P_MONTH_CN = Pattern.compile("(\\d{1,2})\\s*月");
    private static final Pattern P_WEEK = Pattern.compile("(?:(\\d{2}))?\\s*[Ww](\\d{1,2})");
    private static final Pattern P_QUARTER = Pattern.compile("[Qq]([1-4])");
    /** 必须带"期"字："12期" 算，纯数字 12 不算 */
    private static final Pattern P_NTH = Pattern.compile("第?\\s*(\\d{1,3})\\s*期");
    /** 纯数字只在两种场合当"第 N 期"：列名像时间（月报），或列已被确认为时间轴 */
    private static final Pattern P_BARE = Pattern.compile("\\d{1,4}");
    private static final Pattern P_YEAR = Pattern.compile("(?:19|20)\\d{2}");

    /**
     * 把一个时间值归一成可比大小的期序号。
     * 月粒度用 year*12+month；周/季/期号直接取序号——同列内可比即可，跨列不需要可比。
     */
    public static Integer parsePeriod(Object raw) {
        return parsePeriod(raw, true);
    }

    /**
     * allowBare=false 时只接受带明确时间语义的写法。
     * 结构识别必须用严格版——否则订单数 28、金额 4100 这类纯数字全会被解析成"第 28 期 / 第 4100 期"，
     * 于是数值列被认成时间轴，整个分析跑错轴。这是同一个坑的第二个变种。
     */
    public static Integer parsePeriod(Object raw, boolean allowBare) {
        if (raw == null) return null;
        String s = stringify(raw);
        if (s.isEmpty()) return null;
        Matcher m = P_YM6.matcher(s);
        if (m.matches()) return Integer.parseInt(m.group(1)) * 12 + Integer.parseInt(m.group(2));
        m = P_YM.matcher(s);
        if (m.matches()) {
            int mo = Integer.parseInt(m.group(2));
            if (mo >= 1 && mo <= 12) return Integer.parseInt(m.group(1)) * 12 + mo;
        }
        m = P_MONTH_CN.matcher(s);
        if (m.matches()) return Integer.parseInt(m.group(1));
        m = P_WEEK.matcher(s);
        if (m.matches()) return Integer.parseInt(m.group(2));
        m = P_QUARTER.matcher(s);
        if (m.matches()) return Integer.parseInt(m.group(1));
        m = P_NTH.matcher(s);
        if (m.matches()) return Integer.parseInt(m.group(1));
        if (P_YEAR.matcher(s).matches()) return Integer.parseInt(s) * 12;
        if (allowBare && P_BARE.matcher(s).matches()) return Integer.parseInt(s);
        return null;
    }

    /**
     * 结构判断：这一列像不像时间轴。四重约束缺一不可。
     *
     * 第四条是踩了两次坑才补上的：金额、订单数、自增序号这类"逐行递增的数值列"，
     * 排序后相邻差的中位数恰好是 1，完美满足"近似连续"，于是被认成时间轴——
     * 整个分析跑在错的轴上，输出看着挺正常。
     * 时间轴的真正特征是"分组键"：同一期里通常有多条记录。
     */
    private static boolean looksLikeTime(DataTable t, int col) {
        List<Integer> keys = new ArrayList<>();
        for (int r = 0; r < t.rowCount(); r++) {
            Integer k = parsePeriod(t.get(r, col), false);
            if (k == null) return false;
            keys.add(k);
        }
        Set<Integer> uniq = new TreeSet<>(keys);
        if (uniq.size() < 3) return false;
        // 时间轴是"分组键"，不是"取值"：期数不可能接近行数
        if (uniq.size() > Math.max(3, t.rowCount() / 3)) return false;
        // 期值应近似连续：随机整数很难满足"相邻差中位数 <= 2"
        List<Integer> diffs = new ArrayList<>();
        List<Integer> sorted = new ArrayList<>(uniq);
        for (int i = 1; i < sorted.size(); i++) diffs.add(sorted.get(i) - sorted.get(i - 1));
        if (diffs.isEmpty()) return false;
        Collections.sort(diffs);
        double medDiff = diffs.get(diffs.size() / 2);
        if (medDiff > 2) return false;
        // 期均行数 < 1.5 → 几乎每行自成一个"期"，那是数值列不是时间轴
        return t.rowCount() / (double) uniq.size() >= 1.5;
    }

    /**
     * 宽松判断：只给"期数刚好等于行数"的月报表兜底（每期一行，严格约束必然不过）。
     * 条件收紧到"列名必须像时间"，否则金额列也会被捞进来。
     */
    private static boolean looksLikeTimeLoose(DataTable t, int col) {
        if (!containsAny(t.columns.get(col), TIME_HINTS)) return false;
        Set<Integer> uniq = new TreeSet<>();
        for (int r = 0; r < t.rowCount(); r++) {
            Integer k = parsePeriod(t.get(r, col));
            if (k == null) return false;
            uniq.add(k);
        }
        return uniq.size() >= 3;
    }

    // ================= 骨架 =================

    public static SchemaSkeleton detect(DataTable t) {
        SchemaSkeleton s = new SchemaSkeleton();
        if (t == null || t.rowCount() == 0 || t.colCount() == 0) {
            s.notes.add("没有可分析的数据");
            return s;
        }

        // 1) 时间列
        List<int[]> cands = new ArrayList<>();   // [colIdx, periodCount, named]
        for (int c = 0; c < t.colCount(); c++) {
            if (!looksLikeTime(t, c)) continue;
            int periods = new TreeSet<>(collectPeriods(t, c)).size();
            s.timeCandidates.add(t.columns.get(c) + "(" + periods + "期)");
            cands.add(new int[]{c, periods, containsAny(t.columns.get(c), TIME_HINTS) ? 1 : 0});
        }
        cands.sort((a, b) -> a[2] != b[2] ? b[2] - a[2] : b[1] - a[1]);
        // 严格判断全落空时才宽松兜底：月报型数据每期一行，严格约束永远过不了
        if (cands.isEmpty()) {
            for (int c = 0; c < t.colCount(); c++) {
                if (!looksLikeTimeLoose(t, c)) continue;
                int periods = new TreeSet<>(collectPeriods(t, c)).size();
                s.timeCandidates.add(t.columns.get(c) + "(" + periods + "期·每期一行)");
                cands.add(new int[]{c, periods, 1});
            }
        }
        if (!cands.isEmpty()) {
            s.timeCol = t.columns.get(cands.get(0)[0]);
            s.periodCount = cands.get(0)[1];
        }

        // 2) 度量 / 维度
        for (int c = 0; c < t.colCount(); c++) {
            String name = t.columns.get(c);
            if (name.equals(s.timeCol)) continue;
            if (isNumericColumn(t, c)) {
                if (distinctCount(t, c) > 1) s.measures.add(name);
            } else {
                int d = distinctCount(t, c);
                // 维度得有区分度，又不能是主键（每个都不同）
                if (d >= 2 && d <= 60 && d < Math.max(2, t.rowCount())) s.dims.add(name);
            }
        }

        // 3) 语义角色（列名只在这里被用到）
        s.amountCol = pick(t.columns, s.measures, AMOUNT_HINTS);
        s.qtyCol = pick(t.columns, s.measures, QTY_HINTS);
        if (s.amountCol != null && s.qtyCol != null && !s.amountCol.equals(s.qtyCol)) {
            s.priceLabel = "单价（" + s.amountCol + "÷" + s.qtyCol + "）";
        }

        if (s.timeCol == null) {
            s.notes.add("没找到可用的时间列：无法建立自身基线，只能做横截面比较，"
                    + "结论强度会明显下降（不能判断涨跌）");
        } else if (s.periodCount < MIN_PERIODS) {
            s.notes.add("时间列只有 " + s.periodCount + " 期（少于 " + MIN_PERIODS
                    + " 期），样本不足以建立基线，按横截面模式处理");
        }
        if (s.amountCol == null && !s.measures.isEmpty()) {
            s.notes.add("没识别出金额列，主指标用 " + s.measures.get(0) + "；量价拆分不可用");
        }
        return s;
    }

    // ================= 取值工具（供引擎复用） =================

    public static String stringify(Object v) {
        if (v == null) return "";
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return String.valueOf(v).trim();
    }

    public static Double toNum(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        String s = String.valueOf(v).trim().replace(",", "").replace("￥", "").replace("$", "");
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isNumericColumn(DataTable t, int c) {
        int seen = 0;
        for (int r = 0; r < t.rowCount(); r++) {
            Object v = t.get(r, c);
            if (v == null || stringify(v).isEmpty()) continue;
            if (toNum(v) == null) return false;
            if (++seen >= 50) break;    // 抽样 50 行足够判断
        }
        return seen > 0;
    }

    public static int distinctCount(DataTable t, int c) {
        Set<String> s = new HashSet<>();
        for (int r = 0; r < t.rowCount(); r++) s.add(stringify(t.get(r, c)));
        return s.size();
    }

    private static List<Integer> collectPeriods(DataTable t, int c) {
        List<Integer> out = new ArrayList<>();
        for (int r = 0; r < t.rowCount(); r++) {
            Integer k = parsePeriod(t.get(r, c));
            if (k != null) out.add(k);
        }
        return out;
    }

    private static boolean containsAny(String name, String[] hints) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String h : hints) if (lower.contains(h)) return true;
        return false;
    }

    private static String pick(List<String> cols, List<String> pool, String[] hints) {
        for (String c : cols) if (pool.contains(c) && containsAny(c, hints)) return c;
        return null;
    }
}
