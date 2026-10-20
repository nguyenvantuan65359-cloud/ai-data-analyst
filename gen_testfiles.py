#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成几类测试文件，覆盖不同格式与边界情况，用于验证上传+SQL+AI分析链路。"""
import csv, os
from openpyxl import Workbook

OUT = r"E:\springboot"
os.makedirs(OUT, exist_ok=True)

# ---------- 1) sales.csv（含 month 列，验证 H2 保留字 month->MONTH_）----------
csv_path = os.path.join(OUT, "test_sales.csv")
rows = [("month", "region", "category", "sales_amount", "orders")]
regions = ["华东", "华南", "海外"]
cats = ["收纳", "家具", "电子", "服饰"]
import random
random.seed(7)
for m in range(1, 7):
    for r in regions:
        for c in cats:
            amt = random.randint(2000, 12000)
            od = random.randint(10, 80)
            rows.append((m, r, c, amt, od))
with open(csv_path, "w", newline="", encoding="utf-8-sig") as f:
    w = csv.writer(f)
    w.writerows(rows)
print("CSV:", csv_path, "rows=", len(rows) - 1)

# ---------- 2) inventory.xlsx（多数值列，便于统计/相关性）----------
wb = Workbook()
ws = wb.active
ws.title = "库存"
hdr = ["sku", "name", "warehouse", "stock", "unit_price", "last_sold_days", "rating"]
ws.append(hdr)
data = [
    ("A1001", "折叠收纳箱", "上海仓", 320, 39.9, 5, 4.6),
    ("A1002", "金属衣柜", "上海仓", 80, 299.0, 12, 4.3),
    ("A1003", "桌面置物架", "广州仓", 540, 59.0, 3, 4.8),
    ("A1004", "塑料储物柜", "广州仓", 210, 129.0, 21, 4.1),
    ("A1005", "布艺收纳袋", "海外仓", 980, 19.9, 2, 4.5),
    ("A1006", "木质书柜", "上海仓", 45, 459.0, 33, 4.7),
    ("A1007", "抽屉式收纳", "广州仓", 670, 89.0, 7, 4.2),
    ("A1008", "真空压缩袋", "海外仓", 1200, 25.0, 1, 4.6),
    ("A1009", "鞋盒收纳", "上海仓", 430, 35.0, 9, 4.0),
    ("A1010", "衣物防尘罩", "海外仓", 760, 15.5, 4, 4.4),
]
for d in data:
    ws.append(list(d))
# 第二张 sheet（验证：当前代码只读第一张，这里故意放一张无关的，看是否串）
ws2 = wb.create_sheet("说明")
ws2.append(["说明", "本 sheet 仅为测试多 sheet 场景，上传时应被忽略"])
wb.save(os.path.join(OUT, "test_inventory.xlsx"))
print("XLSX:", os.path.join(OUT, "test_inventory.xlsx"), "rows=", len(data))

# ---------- 3) macro_test.xlsm（带宏扩展名，纯数据，验证 xlsm 格式可传）----------
wm = Workbook()
ws = wm.active
ws.title = "季度销售"
wm_hdr = ["product", "q1", "q2", "q3", "q4"]
ws.append(wm_hdr)
wm_data = [
    ("收纳箱", 120, 150, 180, 200),
    ("金属衣柜", 60, 70, 65, 90),
    ("置物架", 300, 280, 320, 350),
    ("储物柜", 90, 110, 105, 130),
    ("收纳袋", 500, 520, 480, 560),
]
for d in wm_data:
    ws.append(list(d))
wm.save(os.path.join(OUT, "test_macro.xlsm"))
print("XLSM:", os.path.join(OUT, "test_macro.xlsm"), "rows=", len(wm_data))

# ---------- 4) edgecase_formulas.xlsx（错误公式单元格，验证健壮性）----------
we = Workbook()
ws = we.active
ws.title = "含错误公式"
we_hdr = ["id", "a", "b", "a_div_b", "note"]
ws.append(we_hdr)
# 写普通数据
ws.append([1, 10, 2, "=A2/B2", "正常"])
ws.append([2, 10, 0, "=A3/B3", "除零 -> #DIV/0!"])   # B=0 -> 错误公式
ws.append([3, 10, 5, "=A4/B4", "正常"])
# 在 C 列放一个指向已删单元格的公式 -> #REF!  (这里用简单写法：=1/0 直接错)
ws["C6"] = "=#REF!"
ws.append([4, 7, 3, "=A6/B6", "正常"])
# 留一个空行
ws.append([5, None, None, None, "空行测试"])
we.save(os.path.join(OUT, "test_edgecase_formulas.xlsx"))
print("XLSX(edge):", os.path.join(OUT, "test_edgecase_formulas.xlsx"))

print("DONE")
