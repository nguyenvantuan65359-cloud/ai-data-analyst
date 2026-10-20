# ============================================================
# 本地「外部库」测试靶子 —— H2 TCP Server
#
# 用途：给本机 AI 数据分析系统提供一个真实的、可通过 JDBC URL 远程连接的
#      数据库，用来验证「数据源配置 → 表清单 → 数据字典 → AI 分析」全链路。
#
# 为什么需要它：
#   连公共 MySQL 测一次要几十秒到几分钟（外网延迟 + 大库 COUNT(*)），
#   而这个靶子：零外网依赖、毫秒级、随时重置、可以灌自己的业务数据。
#
# 用法（Git Bash）：
#   bash start_testdb.sh
# 起好后，页面「数据源」里填：
#   URL      jdbc:h2:tcp://localhost:9092/./testdb/biz;MODE=MySQL
#   用户名   sa
#   密码     （留空）
#
# 停止服务：Ctrl+C，或重新运行前脚本会自动清理旧端口
# ============================================================
cd "$(dirname "$0")"

JAVA_HOME=/d/tools/jdk-21.0.12.1+1
PORT=9092
DBDIR=./testdb
H2JAR=$(tr ';' '\n' < cp.txt | grep -i "h2" | grep -i "\.jar" | head -1)

echo "H2 jar: $H2JAR"

# 先清理可能占用 9092 的旧实例
OLD=$(netstat -ano 2>/dev/null | grep ":${PORT}" | grep LISTEN | awk '{print $5}' | head -1)
if [ -n "$OLD" ]; then
  echo "发现旧服务占用端口 $PORT (PID $OLD)，停止中…"
  taskkill.exe /PID "$OLD" /F >/dev/null 2>&1
  sleep 1
fi

# 建数据库目录（不存在则建）
mkdir -p "$DBDIR"

echo "启动 H2 TCP Server on port $PORT，数据目录 $DBDIR"
echo "连接串：jdbc:h2:tcp://localhost:${PORT}/./${DBDIR}/biz;MODE=MySQL"
echo "按 Ctrl+C 停止"
echo "------------------------------------------------------------"

MSYS_NO_PATHCONV=1 "$JAVA_HOME/bin/java" -cp "$H2JAR" \
  org.h2.tools.Server \
  -tcp \
  -tcpPort "$PORT" \
  -tcpAllowOthers \
  -ifNotExists
