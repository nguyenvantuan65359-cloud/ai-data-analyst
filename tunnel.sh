#!/usr/bin/env bash
# 起一条长期公网隧道（Cloudflare Quick Tunnel），并把访问链接打印出来。
# 用法：bash tunnel.sh           # 起隧道（已有就复用）
#       bash tunnel.sh --restart # 杀掉旧的重新起（地址会变）
#
# 为什么用它：pinggy 免费版 60 分钟就断，一天要重开十几次；
# Cloudflare Quick Tunnel 不注册、不填卡、不设时限——只要进程在跑，链接就一直在。
# 代价：机器重启 / 进程被杀，链接就换新的（免费版无法固定域名）。
set -u
cd "$(dirname "$0")"
if [ -f .env ]; then set -a; . ./.env; set +a; fi
CF=/d/tools/cloudflared/cloudflared.exe
LOG=cf.log

if [ "${1:-}" = "--restart" ]; then
  MSYS_NO_PATHCONV=1 taskkill /IM cloudflared.exe /F >/dev/null 2>&1
  sleep 1
fi

if tasklist 2>/dev/null | grep -qi cloudflared; then
  echo "隧道已在运行："
else
  rm -f "$LOG"
  nohup "$CF" tunnel --url http://localhost:8080 --no-autoupdate > "$LOG" 2>&1 &
  for i in $(seq 1 30); do
    grep -q "trycloudflare.com" "$LOG" 2>/dev/null && break
    sleep 1
  done
fi

URL=$(grep -oE "https://[a-z0-9-]+\.trycloudflare\.com" "$LOG" | head -1)
if [ -z "${URL:-}" ]; then echo "隧道没起来，看 $LOG："; tail -20 "$LOG"; exit 1; fi

CODE="${ANALYST_ACCESS_CODE:-}"
if [ -n "$CODE" ]; then Q="?code=$CODE"; else Q=""; fi
echo "本地：http://localhost:8080/$Q"
echo "公网：$URL/$Q"
curl -s --noproxy '*' -o /dev/null -w "公网首页 HTTP %{http_code}\n" "$URL/$Q" --max-time 30
