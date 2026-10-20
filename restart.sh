#!/usr/bin/env bash
# 一键重编译 + 重启本地 AI 数据分析服务（Git Bash 下运行：bash restart.sh）
# 用法：bash restart.sh            # 编译并重启
#       bash restart.sh --skip-build  # 只重启，不编译
set -u
cd "$(dirname "$0")"

# 密钥与口令一律从环境变量或同目录 .env 读，不写死在脚本里（否则一推 GitHub 就泄露）
if [ -f .env ]; then set -a; . ./.env; set +a; fi

export JAVA_HOME="${JAVA_HOME:-D:/tools/jdk-21.0.12.1+1}"
if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "缺少 DEEPSEEK_API_KEY：请复制 .env.example 为 .env 并填入你的 key"; exit 1
fi
export ANALYST_ACCESS_CODE="${ANALYST_ACCESS_CODE:-}"
CP=$(cat cp.txt)

if [ "${1:-}" != "--skip-build" ]; then
  echo "[1/3] 编译..."
  "$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -d target/classes -cp "target/classes;$CP" \
    $(find src/main/java -name "*.java") || { echo "编译失败，已中止"; exit 1; }
  # 资源整体同步（index.html / application.yml / data.sql / schema.sql 都得是最新的）
  cp -rf src/main/resources/. target/classes/
fi

echo "[2/3] 停旧进程..."
# 注意：Git Bash 会把 /F 当路径转换，必须加 MSYS_NO_PATHCONV=1，否则 taskkill 报"无效参数"，
# 旧进程不死、新进程因端口占用启动失败，健康检查却打到了旧进程上——看起来"重启成功"其实没换代码。
PID=$(netstat -ano 2>/dev/null | grep ":8080" | grep LISTENING | awk '{print $NF}' | head -1)
if [ -n "${PID:-}" ]; then
  MSYS_NO_PATHCONV=1 taskkill /F /PID "$PID" >/dev/null 2>&1 && echo "  已停止 PID $PID"
fi
for i in $(seq 1 15); do
  netstat -ano 2>/dev/null | grep ":8080" | grep -q LISTENING || break
  sleep 1
done
if netstat -ano 2>/dev/null | grep ":8080" | grep -q LISTENING; then
  echo "  端口 8080 仍被占用，强制退出"; exit 1
fi

echo "[3/3] 启动..."
nohup "$JAVA_HOME/bin/java" -cp "target/classes;$CP" com.example.analyst.AnalystApplication \
  --server.port=8080 >> app.log 2>&1 &

for i in $(seq 1 40); do
  code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ || true)
  if [ "$code" = "200" ]; then echo "  启动成功 http://localhost:8080/"; exit 0; fi
  sleep 1
done
echo "  启动超时，查看 app.log："; tail -30 app.log
exit 1
