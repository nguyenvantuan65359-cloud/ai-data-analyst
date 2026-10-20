#!/usr/bin/env bash
# 用法: ./ask.sh "你的问题（可含中文）"
# 把中文问题用 UTF-8 文件传给 /api/analyst/ask，绕开 Git Bash 下 curl 把中文发成 GBK 导致 400 的问题
set -e
Q="$1"
[ -z "$Q" ] && { echo "用法: ./ask.sh \"你的问题\""; exit 1; }
printf '{"question":"%s"}' "$Q" > /tmp/ask_payload.json
curl -s --max-time 120 -X POST http://localhost:8080/api/analyst/ask \
  -H "Content-Type: application/json; charset=utf-8" \
  --data-binary @/tmp/ask_payload.json
echo
