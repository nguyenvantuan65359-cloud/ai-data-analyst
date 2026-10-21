"""
发现引擎原型 v0（M1 的参考实现）

只做最简单的一套判据，验证"能不能挖出值得看的东西"：
  1) 骨架识别：数值列=度量；低基数列=维度；能解析为期的列=时间轴
  2) 逐切片算期序列 → 环比、中位数基线、MAD 稳健 z
  3) **超额变化**（关键）：切片变化 − 按整体增速应有的变化
     整体都在跌时，某切片跌 18% 不算异常；超出大盘的那部分才算
  4) 派生单价：金额/数量（**必须先分别求和再相除**，不能把单价相加）
  5) 按 |超额额| 排序取 Top，正负分开

不做：FDR、CUSUM、双因素残差（M2 再做）
用法：python tools/discover.py test_sales.csv
"""
import csv
import statistics
import sys
from collections import defaultdict


def sniff(rows, cols):
    time_col, measures, dims = None, [], []
    for c in cols:
        vals = [r[c] for r in rows if r[c] not in ("", None)]
        nums, ok = [], True
        for v in vals:
            try:
                nums.append(float(v))
            except ValueError:
                ok = False
                break
        if ok and nums:
            uniq = sorted(set(nums))
            nm = c.lower()
            if (len(uniq) >= 5 and all(float(int(u)) == u for u in uniq) and max(uniq) <= 210012
                    and any(k in nm for k in ("month", "月", "period", "期", "year", "年", "week", "周", "date", "日期"))):
                time_col = c
                continue
            measures.append(c)
        else:
            if 1 < len(set(vals)) <= 50:
                dims.append(c)
    return time_col, measures, dims


def mad_z(series, x):
    """稳健 z：中位数 + MAD。期数 <8 时仅供参考，不作判据"""
    if len(series) < 3:
        return None
    med = statistics.median(series)
    mad = statistics.median([abs(v - med) for v in series])
    if mad == 0:
        return None
    return (x - med) / (1.4826 * mad)


def build(rows, time_col, dims, measure, amount=None, qty=None):
    """切片 → 期 → 值。单价按 SUM(金额)/SUM(数量) 聚合"""
    total_a, total_q = defaultdict(float), defaultdict(float)
    by_slice_a, by_slice_q = defaultdict(lambda: defaultdict(float)), defaultdict(lambda: defaultdict(float))
    for r in rows:
        p = float(r[time_col])
        key = tuple(r[d] for d in dims) if dims else ("整体",)
        if amount:
            a, q = float(r[amount] or 0), float(r[qty] or 0)
            total_a[p] += a
            total_q[p] += q
            by_slice_a[key][p] += a
            by_slice_q[key][p] += q
        else:
            v = float(r.get(measure) or 0)
            total_a[p] += v
            by_slice_a[key][p] += v

    periods = sorted(total_a)

    def overall(p):
        if amount:
            return total_a[p] / total_q[p] if total_q[p] else 0
        return total_a[p]

    def slice_val(key, p):
        if amount:
            q = by_slice_q[key].get(p, 0)
            return by_slice_a[key].get(p, 0) / q if q else 0
        return by_slice_a[key].get(p, 0)

    return periods, overall, slice_val, list(by_slice_a.keys())


def scan(rows, time_col, measure, dims, amount=None, qty=None):
    periods, overall, slice_val, keys = build(rows, time_col, dims, measure, amount, qty)
    if len(periods) < 3:
        return None
    last, prev = periods[-1], periods[-2]
    t_last, t_prev = overall(last), overall(prev)
    growth = (t_last - t_prev) / t_prev if t_prev else 0
    total_delta = t_last - t_prev

    out = []
    for key in keys:
        series = [slice_val(key, p) for p in periods]
        v_last, v_prev = series[-1], series[-2]
        delta = v_last - v_prev
        expected = v_prev * growth
        excess = delta - expected
        out.append({
            "slice": key, "periods": periods, "series": series,
            "last": v_last, "prev": v_prev, "delta": delta,
            "pct": (delta / v_prev * 100) if v_prev else None,
            "excess": excess,
            "excess_pct": (excess / v_prev * 100) if v_prev else None,
            "excess_share": (excess / abs(total_delta) * 100) if total_delta else None,
            "z": mad_z(series, v_last),
            "first": series[0],
            "all_pct": ((series[-1] - series[0]) / series[0] * 100) if series[0] else None,
        })
    return {"periods": periods, "overall": overall, "growth": growth,
            "total_delta": total_delta, "rows": out}


def show(measure, dims, r):
    periods, overall = r["periods"], r["overall"]
    print("\n" + "=" * 80)
    print(f"【{measure}】按 {' × '.join(dims)}｜期数 {len(periods)}")
    print("  整体：" + "  ".join(f"{p:g}={overall(p):,.0f}" for p in periods))
    print(f"  整体环比 {r['growth'] * 100:+.1f}%（Δ={r['total_delta']:,.0f}）")
    print(f"\n  {'切片':<18}{'本期':>10}{'上期':>9}{'环比%':>8}{'超额':>10}{'占整体Δ%':>10}{'z':>7}{'全期%':>8}")
    for x in sorted(r["rows"], key=lambda v: -abs(v["excess"]))[:6]:
        print(f"  {'×'.join(str(k) for k in x['slice']):<18}{x['last']:>10,.1f}{x['prev']:>9,.1f}"
              f"{(x['pct'] or 0):>8.1f}{x['excess']:>10,.1f}"
              f"{(x['excess_share'] or 0):>10.1f}"
              f"{(x['z'] if x['z'] is not None else 0):>7.2f}"
              f"{(x['all_pct'] or 0):>8.1f}")


def main(path):
    rows = list(csv.DictReader(open(path, encoding="utf-8-sig")))
    cols = list(rows[0].keys())
    time_col, measures, dims = sniff(rows, cols)
    print(f"骨架：时间={time_col}｜度量={measures}｜维度={dims}｜行数={len(rows)}")
    if not time_col:
        print("未识别到时间列 → 只能走模式 B，本原型不覆盖")
        return

    amount = next((m for m in measures if any(k in m.lower() for k in ("amount", "金额", "sales", "revenue", "gmv"))), None)
    qty = next((m for m in measures if any(k in m.lower() for k in ("order", "qty", "数量", "单量", "销量"))), None)

    dim_sets = [[d] for d in dims] + ([dims] if len(dims) >= 2 else [])
    for measure in measures:
        for ds in dim_sets:
            r = scan(rows, time_col, measure, ds)
            if r:
                show(measure, ds, r)

    if amount and qty:
        for ds in dim_sets:
            r = scan(rows, time_col, "_price", ds, amount=amount, qty=qty)
            if r:
                show("单价（金额/数量）", ds, r)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "test_sales.csv")
