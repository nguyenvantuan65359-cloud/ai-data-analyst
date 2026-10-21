"""
三个刻意构造的场景，验证判据在极端情况下会不会崩。
这不是"效果好不好"的测试——没有真实数据就没有价值可验；这里只验工程行为。

场景 A：时间模式·健康（12 期）——埋一个真异常 + 一个假异常（小样本）
场景 B：无时间列——应自动降级为横截面模式，且不得出现"上涨/下滑"字样
场景 C：时间藏在字段里（2026-09 这种）——应能解构出来并走时间模式
"""
import csv
import os
import random
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "_selftest")


def write(name, cols, rows):
    os.makedirs(OUT, exist_ok=True)
    p = os.path.join(OUT, name)
    with open(p, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(cols)
        w.writerows(rows)
    return p


def scenario_a():
    """12 期 × 3 地区 × 3 品类；华南×收纳在最后 3 期腰斩（真异常）；一个 n=2 的切片做假异常"""
    random.seed(7)
    rows = []
    for m in range(1, 13):
        for reg, base in (("华东", 4000), ("华南", 3800), ("海外", 3600)):
            for cat, k in (("收纳", 1.0), ("服饰", 0.8), ("电子", 0.9)):
                v = base * k * random.uniform(0.9, 1.1)
                q = max(1, int(v / random.uniform(80, 120)))
                if reg == "华南" and cat == "收纳" and m >= 10:
                    v *= 0.35
                    q = int(q * 0.8)
                if reg == "海外" and cat == "电子" and m == 12:
                    v = v * 3          # 单点尖刺
                    q = 2              # 但分母只有 2 → 应被拦
                rows.append([m, reg, cat, round(v, 2), q])
    return write("a_time.csv", ["month", "region", "category", "amount", "qty"], rows)


def scenario_b():
    """无时间列：只有地区×品类的一次性汇总"""
    random.seed(11)
    rows = []
    for reg, base in (("华东", 4000), ("华南", 3800), ("海外", 3600)):
        for cat, k in (("收纳", 1.0), ("服饰", 0.8), ("电子", 0.9), ("家具", 0.7)):
            for _ in range(6):                       # 每切片 6 行，n 够
                v = base * k * random.uniform(0.85, 1.15)
                if reg == "华东" and cat == "服饰":
                    v *= 1.9                          # 埋一个结构性偏高
                rows.append([reg, cat, round(v, 2), int(v / 100)])
    for _ in range(3):                               # 小样本高值，应被拦
        rows.append(["华东", "家具", 9000, 30])
    return write("b_cross.csv", ["region", "category", "amount", "qty"], rows)


def scenario_c():
    """时间藏在 '2026-09' 这种字符串里"""
    random.seed(3)
    rows = []
    for y, ms in (("2026", range(7, 13)), ("2027", range(1, 4))):
        for m in ms:
            for reg, base in (("华东", 4000), ("华南", 3800)):
                for cat, k in (("收纳", 1.0), ("服饰", 0.8)):
                    v = base * k * random.uniform(0.9, 1.1)
                    if reg == "华东" and cat == "服饰" and (y, m) >= ("2027", 1):
                        v *= 1.6     # 后期抬升 → 应是增长点
                    rows.append([f"{y}-{m:02d}", reg, cat, round(v, 2), max(1, int(v / 100))])
    return write("c_hidden_time.csv", ["period", "region", "category", "amount", "qty"], rows)


def main():
    files = [("A 时间模式·健康", scenario_a()), ("B 无时间列", scenario_b()), ("C 时间藏字段", scenario_c())]
    for label, p in files:
        print("\n" + "#" * 82)
        print(f"# 场景 {label} → {os.path.basename(p)}")
        print("#" * 82)
        out = subprocess.run([sys.executable, os.path.join(HERE, "discover.py"), p],
                             capture_output=True, text=True, encoding="utf-8")
        text = out.stdout
        print(text[:2600])
        if out.stderr.strip():
            print("[stderr]", out.stderr.strip()[:400])

        # 断言
        if label.startswith("A"):
            ok1 = "华南×收纳" in text and "被拦下" in text
            print(f"\n  ✅ 抓到真异常并拦下小样本：{ok1}")
        if label.startswith("B"):
            ok2 = "B 横截面模式" in text and "上涨" not in text and "环比" not in text
            print(f"\n  ✅ 正确降级为横截面且不使用趋势措辞：{ok2}")
        if label.startswith("C"):
            ok3 = "A 时间模式" in text
            print(f"\n  ✅ 从 '2026-09' 解构出时间：{ok3}")


if __name__ == "__main__":
    main()
