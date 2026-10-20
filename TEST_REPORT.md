# 数据分析自助系统 — 端到端自测报告

测试时间：2026-10-16　环境：本地 Spring Boot 4.1 + Spring AI 2.0 + DeepSeek-reasoner，数据源 H2 内存库（sales 表，26 行示例数据）
服务地址：`http://localhost:8080`

## 测试结果

| # | 场景 | 请求 | 预期 | 实际 | 结论 |
|---|------|------|------|------|------|
| 1 | 用户手写 SQL（聚合） | `POST /api/analyst/sql` `SELECT region, SUM(sales_amount)... GROUP BY region` | 返回聚合结果 | `华东 2.395E5 / 华南 1.519E5 / 海外 1.302E5` | ✅ |
| 2 | 只读闸门拦截 DELETE | `POST /api/analyst/sql` `DELETE FROM sales` | 拒绝，不执行 | HTTP **400** `{"status":"rejected","reason":"只允许 SELECT 查询，禁止 Delete"}` | ✅ |
| 3 | 只读闸门拦截 DROP | `POST /api/analyst/sql` `DROP TABLE sales` | 拒绝 | HTTP **400** 同结构 | ✅ |
| 4 | 文件导入 CSV | `POST /api/analyst/upload`（test_upload.csv，6 行） | 解析入库 | HTTP 200，返回 6 行预览 | ✅ |
| 5 | 上传文件可经 SQL 查询 | `POST /api/analyst/sql` `SELECT region, SUM(amount)... FROM uploaded_data` | 能查到上传的数据 | `海外 7.6E4 / 华南 3.86E4 / 华东 2.09E4` | ✅ |
| 6 | AI 分析（种子数据） | `POST /api/analyst/ask` "华东 3 月哪个品类卖最好" | 用 JDBC 取数+给结论 | 调用 queryDatabase → 电子 36,000，含方法说明与业务启示 | ✅ |
| 7 | AI 分析（上传数据） | `POST /api/analyst/ask` "上传数据哪个地区总金额最高" | 查 uploaded_data | 海外 76,000（≈56%），含排名表与离散度洞察 | ✅ |
| 8 | 首页 | `GET /` | 静态页 | HTTP 200 | ✅ |

## 本轮修复的两个关键断点

1. **上传文件查不到（核心断点）**：原本上传只存内存 `AnalysisSession`，而用户 SQL 与 AI 的 `queryDatabase` 工具都走 H2 → 上传的数据根本无法被查询（500）。
   已修：上传时把 `DataTable` **物化进 H2 表 `uploaded_data`**（列类型按抽样推断：数值→DOUBLE，其余→VARCHAR；列名做安全化并避开 H2 保留字，如 `month`→`MONTH_`）。现在上传即"可被 SQL/AI 直接查的真实表"。
2. **只读闸门返回笼统 500**：改为 `@ExceptionHandler` 捕获 `SecurityException/IllegalArgumentException`，返回清晰 **400 + 中文原因**。

## 已知客户端坑（非后端 bug）

Git Bash 下 `curl -d '{"question":"中文"}'` 会把中文按 **GBK** 发送，后端报 `Invalid UTF-8 start byte 0xbb` → HTTP 400。
解决：JSON 写入 UTF-8 文件后 `--data-binary @file`；或统一用仓库里的 `ask.sh "你的问题"`。

## 启动命令（本机已验证）

```bash
cd E:/springboot
export DEEPSEEK_API_KEY=sk-fb8c...fa1        # 或依赖永久用户级变量
export JAVA_HOME=/d/tools/jdk-21.0.12.1+1
CP=$(cat cp.txt)
java -cp "target/classes;$CP" com.example.analyst.AnalystApplication --server.port=8080
```

## 遗留注意点

- `AnalysisSession` 是单例、进程级共享，多用户并发会串（已在该类注释标注，后续可按 sessionId 隔离）。
- DeepSeek-reasoner 响应较慢，单次 ask 约 10~30s，调用方需留足超时（测试用 120s）。
- 端口 49581 实际是 **WorkBuddy 宿主进程**占用，切勿 kill；服务固定用 8080。
