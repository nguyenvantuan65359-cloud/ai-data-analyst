#!/usr/bin/env bash
# ============================================================
# 本地 AI 分析系统 —— 一键自测脚本
# 用法: 在 Git Bash 里 cd 到本项目目录，执行  bash selftest.sh
# 前提: 服务已在 http://localhost:8080 运行。
#       若没起，见脚本底部「启动命令」。
# ============================================================
set -u
BASE="http://localhost:8080"
PASS=0; FAIL=0
ok(){ echo "  [PASS] $1"; PASS=$((PASS+1)); }
no(){ echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }

echo "== 0) 服务存活检测 =="
code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$BASE/")
[ "$code" = "200" ] && ok "GET / -> $code" || no "GET / -> $code  (服务没起来? 见底部启动命令)"

echo "== 1) 用户手写 SELECT（聚合查询）=="
resp=$(curl -s -X POST "$BASE/api/analyst/sql" -H "Content-Type: application/json" \
  -d '{"sql":"SELECT region, ROUND(SUM(sales_amount),0) AS total FROM sales GROUP BY region ORDER BY total DESC"}')
echo "  $resp" | head -c 400; echo
echo "$resp" | grep -q "华东" && ok "SELECT 返回数据" || no "SELECT 无预期数据"

echo "== 2) 只读闸门: DELETE 应被拒 (HTTP 400) =="
resp=$(curl -s -w " [%{http_code}]" -X POST "$BASE/api/analyst/sql" -H "Content-Type: application/json" -d '{"sql":"DELETE FROM sales"}')
echo "  $resp"
echo "$resp" | grep -q "rejected" && ok "DELETE 被拒" || no "DELETE 未被拒"

echo "== 3) 只读闸门: DROP 应被拒 (HTTP 400) =="
resp=$(curl -s -w " [%{http_code}]" -X POST "$BASE/api/analyst/sql" -H "Content-Type: application/json" -d '{"sql":"DROP TABLE sales"}')
echo "  $resp"
echo "$resp" | grep -q "rejected" && ok "DROP 被拒" || no "DROP 未被拒"

echo "== 4) 上传 CSV（含 month 列，验证 H2 保留字处理 month->MONTH_）=="
# 注意: 必须用相对/Windows 路径，Git Bash 下 curl 的 @/tmp/... 不转换会读空
cat > ./selftest_upload.csv <<'CSV'
month,region,amount
1,华东,1000
2,华南,2000
3,海外,3000
4,华东,1500
CSV
resp=$(curl -s -w " [%{http_code}]" -X POST "$BASE/api/analyst/upload" -F "file=@selftest_upload.csv;type=text/csv")
echo "  $resp"
echo "$resp" | grep -q "200" && ok "上传成功" || no "上传失败"

echo "== 5) 上传文件可被 SQL 查询（历史断点，已修）=="
resp=$(curl -s -X POST "$BASE/api/analyst/sql" -H "Content-Type: application/json" \
  -d '{"sql":"SELECT region, SUM(amount) AS total FROM uploaded_data GROUP BY region ORDER BY total DESC"}')
echo "  $resp"
echo "$resp" | grep -q "海外" && ok "uploaded_data 可查" || no "uploaded_data 查不到"

echo "== 5b) 证明 month 列已安全化为 MONTH_（H2 保留字处理）=="
resp=$(curl -s -X POST "$BASE/api/analyst/sql" -H "Content-Type: application/json" \
  -d '{"sql":"SELECT MONTH_, SUM(amount) AS total FROM uploaded_data GROUP BY MONTH_ ORDER BY MONTH_"}')
echo "  $resp"
echo "$resp" | grep -q "MONTH_" && ok "month 已安全化为 MONTH_" || no "MONTH_ 未生效"

echo "== 6) AI 分析（中文问题，UTF-8 文件绕过 Git Bash 的 GBK 坑）=="
printf '{"question":"上传的数据里哪个地区总金额最高？金额是多少？"}' > ./selftest_ask.json
resp=$(curl -s --max-time 120 -X POST "$BASE/api/analyst/ask" -H "Content-Type: application/json; charset=utf-8" --data-binary @selftest_ask.json)
echo "  ${resp:0:500}"
echo "$resp" | grep -q "海外" && ok "AI 给出结论" || no "AI 无结论"

echo
echo "========================================="
echo " 结果:  PASS=$PASS   FAIL=$FAIL"
echo "========================================="

# ------------------------------------------------------------
# 启动命令（服务没起来时用；JDK/Maven 已装在 D:\tools）：
#   export DEEPSEEK_API_KEY=你的key        # 已设为永久用户变量时可省略
#   export JAVA_HOME=/d/tools/jdk-21.0.12.1+1
#   export PATH=$JAVA_HOME/bin:$PATH
#   cd /e/springboot
#   CP=$(cat cp.txt)
#   MSYS_NO_PATHCONV=1 java -cp "target/classes;$CP" \
#       com.example.analyst.AnalystApplication --server.port=8080
# ------------------------------------------------------------
