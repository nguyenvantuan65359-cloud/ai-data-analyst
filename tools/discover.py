"""
发现引擎原型 v1 —— 双模式 + 时间解构 + 样本闸门（M1 Scanner + Judge 的参考实现）

判据（时间模式）：
  环比变化、3 期累计变化、超额变化（切片变化 − 按大盘增速应有的变化）、
  中位数/MAD 稳健 z、分母闸门（比率类指标分母骤降/过小 → 不判定）

判据（横截面模式）：
  O/E 期望值法 + 泊松残差 z；n 不足则不判定；措辞不得使用"上涨/下滑"

用法：python tools/discover.py <csv>
"""
import csv
import re
import statistics
import sys
from collections import defaultdict

MIN_N_RATIO = 15          # 比率类指标的最小分母
MIN_N_CROSS = 8           # 横截面判定的最小样本
MIN_PERIODS = 5           # 达到此期数才走时间模式
TIME_HINTS = ("month", "月", "period", "期", "year", "年", "week", "周", "date", "日期")


# ---------------- 时间解构 ----------------

def parse_period(v):
    if v is None:
        return None
    s = str(v).strip()
    if not s:
        return None
    if re.fullmatch(r"(19|20)\d{4}", s):
        return int(s)
    m = re.fullmatch(r"((?:19|20)\d{2})[-/年.]?(\d{1,2})", s)
    if m:
        return int(m.group(1)) * 12 + int(m.group(2))
    m = re.fullmatch(r"(\d{1,2})月", s)
    if m:
        return int(m.group(1))
    m = re.fullmatch(r"(?:(\d{2}))?[Ww](\d{1,2})", s)
    if m:
        return int(m.group(2))
    m = re.fullmatch(r"[Qq]([1-4])", s)
    if m:
        return int(m.group(1))
    m = re.fullmatch(r"第?(\d{1,3})期?", s)
    if m:
        return int(m.group(1))
    return None


def looks_like_time(col, rows):
    """
    三重约束，缺一不可：
      1) 全列都能解析成期
      2) 期数 <= 行数/3（时间轴是分组键，不是取值）
      3) 期值近似连续（相邻差中位数 <= 2）
    只按"取值是小整数"判断会把 qty 认成时间，整个分析跑错轴 —— 这个坑踩过一次。
    """
    keys = []
    for r in rows:
        k = parse_period(r[col])
        if k is None:
            return False, []
        keys.append(k)
    u = sorted(set(keys))
    if len(u) < 3 or len(u) > max(3, len(rows) / 3):
        return False, u
    diffs = sorted(b - a for a, b in zip(u, u[1:]))
    if not diffs or statistics.median(diffs) > 2:
        return False, u
    return True, u


def choose_time(rows, cols):
    cands = []
    for c in cols:
        ok, u = looks_like_time(c, rows)
        if ok:
            named = any(h in c.lower() for h in TIME_HINTS)
            cands.append((c, len(u), named))
    cands.sort(key=lambda x: (not x[2], -x[1]))
    return (cands[0][0] if cands else None), [(c, n) for c, n, _ in cands]


def skeleton(rows, cols):
    time_col, tcands = choose_time(rows, cols)
    measures, dims = [], []
    for c in cols:
        if c == time_col:
            continue
        vals = [r[c] for r in rows if r[c] not in ("", None)]
        nums, ok = [], True
        for v in vals:
            try:
                nums.append(float(v))
            except ValueError:
                ok = False
                break
        if ok and nums:
            measures.append(c)
        elif 1 < len(set(vals)) <= 60:
            dims.append(c)
    return time_col, measures, dims, tcands


# ---------------- 工具 ----------------

def mad_z(series, x):
    if len(series) < 3:
        return None
    med = statistics.median(series)
    mad = statistics.median([abs(v - med) for v in series])
    if mad == 0:
        return None
    return (x - med) / (1.4826 * mad)


def tri_sum(series, n=3):
    """3 期对 3 期：返回 (变化率, 变化额)"""
    if len(series) < 2 * n:
        return None, None
    a, b = sum(series[-n:]), sum(series[-2 * n:-n])
    return ((a - b) / b if b else None), (a - b)


# ---------------- 模式 A ----------------

def scan_time(rows, time_col, measure, dims, amount=None, qty=None):
    ta, tq = defaultdict(float), defaultdict(float)
    sa = defaultdict(lambda: defaultdict(float))
    sq = defaultdict(lambda: defaultdict(float))

    for r in rows:
        p = parse_period(r[time_col])
        key = tuple(r[d] for d in dims) if dims else ("整体",)
        if amount:
            a, q = float(r[amount] or 0), float(r[qty] or 0)
            ta[p] += a
            tq[p] += q
            sa[key][p] += a
            sq[key][p] += q
        else:
            v = float(r.get(measure) or 0)
            ta[p] += v
            sa[key][p] += v
            if qty:                       # 数量单独累计，用于量价背离检查
                sq[key][p] += float(r[qty] or 0)

    periods = sorted(ta)
    if len(periods) < 3:
        return None

    def overall(p):
        return (ta[p] / tq[p]) if (amount and tq[p]) else ta[p]

    def sval(key, p):
        if amount:
            return (sa[key].get(p, 0) / sq[key][p]) if sq[key].get(p) else 0.0
        return sa[key].get(p, 0)

    last, prev = periods[-1], periods[-2]
    t_last, t_prev = overall(last), overall(prev)
    growth = (t_last - t_prev) / t_prev if t_prev else 0.0
    total_delta = t_last - t_prev
    g3, _ = tri_sum([overall(p) for p in periods])

    res = []
    for key in sa:
        series = [sval(key, p) for p in periods]
        v_last, v_prev = series[-1], series[-2]
        delta = v_last - v_prev
        excess = delta - v_prev * growth

        _, d3 = tri_sum(series)
        base3 = sum(series[-6:-3]) if len(series) >= 6 else None
        exc3 = (d3 - base3 * g3) if (d3 is not None and base3 is not None and g3 is not None) else None

        q_hist = [sq[key].get(p, 0) for p in periods] if qty else []
        q_last = q_hist[-1] if q_hist else 0
        q_prev = q_hist[-2] if len(q_hist) >= 2 else 0
        q_med = statistics.median([q for q in q_hist[:-1] if q]) if any(q_hist[:-1]) else 0
        reason = ""
        if amount and q_last < MIN_N_RATIO:
            reason = "分母过小（%d < %d）" % (q_last, MIN_N_RATIO)
        elif amount and q_med and q_last < 0.3 * q_med:
            reason = "分母骤降至 %d（常期约 %d）" % (q_last, round(q_med))
        elif qty and q_prev > 0:
            # 量价严重背离：数量骤降 >70% 而金额在涨（或反过来）→ 多为数据断裂或极端单笔
            q_chg = (q_last - q_prev) / q_prev
            if abs(q_chg) > 0.7 and (delta * q_chg) < 0:
                reason = "量价严重背离（量 %+.0f%% / 金额 %+.0f%%）" % (q_chg * 100, (delta / v_prev * 100) if v_prev else 0)

        res.append({
            "slice": key, "series": series, "last": v_last, "prev": v_prev,
            "delta": delta, "pct": (delta / v_prev * 100) if v_prev else None,
            "excess": excess, "exc3": exc3,
            "share": (delta / abs(total_delta) * 100) if total_delta else None,
            "z": mad_z(series, v_last), "blocked": bool(reason), "reason": reason,
        })

    return {"periods": periods, "overall": overall, "growth": growth,
            "total_delta": total_delta, "rows": res}


# ---------------- 模式 B ----------------

def scan_cross(rows, measure, dims):
    total = sum(float(r[measure] or 0) for r in rows)
    n_all = len(rows)
    if not total or not n_all:
        return None
    agg = defaultdict(lambda: [0.0, 0])
    for r in rows:
        key = tuple(r[d] for d in dims) if dims else ("整体",)
        agg[key][0] += float(r[measure] or 0)
        agg[key][1] += 1

    res = []
    for key, (v, n) in agg.items():
        exp = total * n / n_all
        if exp <= 0:
            continue
        res.append({
            "slice": key, "value": v, "expected": exp, "oe": v / exp,
            "z": (v - exp) / (exp ** 0.5), "n": n, "blocked": n < MIN_N_CROSS,
        })
    return {"total": total, "rows": res}


# ---------------- 输出 ----------------

def show_time(label, r):
    head = "| " + " | ".join("%s" % p for p in r["periods"])
    print("")
    print("=" * 78)
    print("[%s] 时间模式 | 期数 %d" % (label, len(r["periods"])))
    print("  整体序列：" + "  ".join("%s=%.0f" % (p, r["overall"](p)) for p in r["periods"]))
    print("  整体环比 %+.1f%%（Δ=%.0f）" % (r["growth"] * 100, r["total_delta"]))

    ok = [x for x in r["rows"] if not x["blocked"]]
    bad = [x for x in r["rows"] if x["blocked"]]

    def rank(x):
        v = x["exc3"] if x["exc3"] is not None else (x["excess"] * 3)
        return -abs(v)

    print("")
    print("  切片                 本期       上期    环比%    超额1期    3期超额      z")
    for x in sorted(ok, key=rank)[:7]:
        e3 = x["exc3"] if x["exc3"] is not None else 0
        name = "×".join(str(k) for k in x["slice"])
        print("  %-18s%10.1f%9.1f%8.1f%10.1f%10.1f%7.2f"
              % (name, x["last"], x["prev"], x["pct"] or 0, x["excess"], e3, x["z"] or 0))

    if bad:
        print("")
        print("  【被拦下：指标失真，不判定】")
        for x in sorted(bad, key=lambda v: -abs(v["excess"]))[:4]:
            name = "×".join(str(k) for k in x["slice"])
            print("  %-18s%10.1f%9.1f%8.1f   <- %s"
                  % (name, x["last"], x["prev"], x["pct"] or 0, x["reason"]))


def show_cross(label, r):
    print("")
    print("=" * 78)
    print("[%s] 横截面模式（措辞不含趋势判断）" % label)
    print("  总量 %.0f" % r["total"])
    print("")
    print("  切片                 实际       期望    O/E   残差z     n")
    ok = [x for x in r["rows"] if not x["blocked"]]
    bad = [x for x in r["rows"] if x["blocked"]]
    for x in sorted(ok, key=lambda v: -abs(v["z"]))[:6]:
        name = "×".join(str(k) for k in x["slice"])
        print("  %-18s%10.0f%10.0f%7.2f%8.2f%5d"
              % (name, x["value"], x["expected"], x["oe"], x["z"], x["n"]))
    if bad:
        print("")
        print("  【被拦下：n < %d】" % MIN_N_CROSS)
        for x in bad[:4]:
            name = "×".join(str(k) for k in x["slice"])
            print("  %-18s%10.0f                        %5d" % (name, x["value"], x["n"]))


def run(path):
    rows = list(csv.DictReader(open(path, encoding="utf-8-sig")))
    cols = list(rows[0].keys())
    time_col, measures, dims, tcands = skeleton(rows, cols)
    n_periods = len({parse_period(r[time_col]) for r in rows}) if time_col else 0
    mode = "A" if (time_col and n_periods >= MIN_PERIODS) else "B"

    print("文件 %s | 行数 %d" % (path, len(rows)))
    print("骨架：时间候选=%s 选定=%s | 度量=%s | 维度=%s"
          % ([c for c, _ in tcands], time_col, measures, dims))
    print("模式判定：%s（识别到 %d 期）" % ("A 时间模式" if mode == "A" else "B 横截面模式", n_periods))

    amount = next((m for m in measures if any(k in m.lower() for k in ("amount", "金额", "sales", "revenue", "gmv"))), None)
    qty = next((m for m in measures if any(k in m.lower() for k in ("order", "qty", "数量", "单量", "销量"))), None)
    dim_sets = [[d] for d in dims] + ([dims] if len(dims) >= 2 else [])

    if mode == "A":
        for measure in measures:
            for ds in dim_sets:
                r = scan_time(rows, time_col, measure, ds, qty=qty)
                if r:
                    show_time("%s × %s" % (measure, "×".join(ds)), r)
        if amount and qty:
            for ds in dim_sets:
                r = scan_time(rows, time_col, "_price", ds, amount=amount, qty=qty)
                if r:
                    show_time("单价(金额/数量) × %s" % "×".join(ds), r)
    else:
        for measure in measures:
            for ds in dim_sets:
                r = scan_cross(rows, measure, ds)
                if r:
                    show_cross("%s × %s" % (measure, "×".join(ds)), r)


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "test_sales.csv")
