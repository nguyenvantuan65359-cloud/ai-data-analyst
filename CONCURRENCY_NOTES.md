# 多人同时用：会话隔离改造说明

> 状态：会话隔离已完成并通过 6 项 e2e 实测 ｜ 服务运行中（含 access-code `YOUR_CODE`）
> 改造时间：2026-10-16

---

## 一、这次改了什么

**之前的问题（数据事故风险）**：
- `AnalysisSession` 是进程级单例，A 上传的文件会覆盖 B 的当前表
- `DataIngestion.loadIntoH2` 硬编码落进 `uploaded_data` 这一张表，A 上传 = 把 B 的表 DROP + 重建
- `ExternalDb` / `DbProfile` 全局共享，A 连的库 B 也能查

**改完**：
1. **`AnalysisSession`**：从单字段改成 `ConcurrentMap<sid, DataTable>`，按 sessionId 拿/放当前表。
2. **`uploaded_data` 表名按 sid 拼**：`uploaded_data_alice` / `uploaded_data_bob` / `uploaded_data_default`。
3. **SQL 自动重写**：用户/AI 写 `uploaded_data` 字面 → 自动改写到本会话的表名；写 `uploaded_data_<其他 sid>` 直接被闸门拒绝。
4. **闸门新增第三道防线（SqlGuard）**：识别 `UPLOADED_DATA_xxx` 中 xxx 是否等于本会话 sid，否则拒绝。
5. **入口透传 sid**：`/upload`（form 字段）、`/sql`（JSON body）、`/ask`（已有）三个端点都把 sessionId 传给后端。
6. **AI 提示词追加"本会话隔离信息"**：`sessionId` 与对应的 `uploaded_data_<safeSid>` 表名都告诉 AI，让它自己拼 SQL 时不会写错。
7. **前端 fetch**：上传与手写 SQL 都自动带 `sessionId` 字段（`SESSION_ID` 来自 localStorage，浏览器间天然隔离）。

---

## 二、实测结果（六项 e2e，全部 PASS）

| # | 场景 | 期望 | 实际 |
|---|---|---|---|
| 1 | Alice 上传 `test_sales.csv` | 表 = `uploaded_data_alice`，行数 72 | ✅ |
| 2 | Bob 上传 `test_inventory.xlsx` | 表 = `uploaded_data_bob`，行数 10 | ✅ |
| 3 | Alice 查 `SELECT COUNT(*) FROM uploaded_data` | 自动重写到 alice，72 | ✅ |
| 4 | Bob 查 `SELECT COUNT(*) FROM uploaded_data` | 自动重写到 bob，10 | ✅ |
| 5 | Alice 写 `SELECT ... FROM uploaded_data_bob` | 400 + 中文拒绝："只能查 alice，禁止 bob" | ✅ |
| 6 | Alice 显式查 `uploaded_data_alice` | 200 / 72 | ✅ |

复跑命令：
```bash
H="X-Access-Code: YOUR_CODE"
curl -X POST -H "$H" -F "file=@test_sales.csv" -F "sessionId=alice" \
  http://localhost:8080/api/analyst/upload
# ... 把 sessionId 换 bob，sql 改 "FROM uploaded_data_bob" 验跨会话被拒
```

---

## 三、没动什么（仍存在但降级了）

| 模块 | 现状 | 后果 | 何时必须改 |
|---|---|---|---|
| `ExternalDb` 连接 | **全局单例** | 所有人看到同一个外部库；A 连的库 B 也能查 | 业务上不同人需要连不同的库时 |
| `DbProfile` 字典 | **全局共享** `db_profile.md` | 字典会反映最后连库那人的库；A 写"我们公司销售表"也 B 看得到 | 想要每个人有独立业务术语记忆时 |
| `AnalysisSession` 当前表 | **按 sid 隔离** ✅ | 无 | — |
| `uploaded_data` | **按 sid 隔离** ✅ | 无 | — |
| `ChatSessions` 记忆 | **按 sid 隔离**（之前已做） | 无 | — |

> **现实建议**：跨境电商公司里"几个人共享同一个业务库"的场景下，全局单例的 ExternalDb 反而是优点——大家都用一致的字典与连接。只有在"每个人要看不同的库"时才需要把这两个也按 sid 隔离。

---

## 四、潜在的会话 ID 注入风险（已堵）

- `DataIngestion.safeSid(sid)` 用白名单 `[A-Za-z0-9_]` 过滤 + 长度截断 24，所以表名 `UPLOADED_DATA_<sid>` 不会因 sessionId 含特殊字符而出错或撞表。
- 闸门禁止写入 / `DROP` / `CREATE`，只允许 `SELECT`。
- 闸门禁止跨会话读：`uploaded_data_<其他 sid>` 在解析 SQL 时就拦掉。

---

## 五、这次也修了一个有意思的 bug

- **症状**：Alice 跨会话查 Bob 时，错误 message 说"只能查 `uploaded_data_default`"，明显不对。
- **根因**：`AnalystController.sql()` 第一行还在调旧版 `guard.assertReadOnly(sql)`（不带 sid），它内部转 `assertReadOnly(sql, null)`，`safeSid(null)="default"`，所以报"default"而不是"alice"。
- **修法**：删掉 controller 里那行冗余调用。闸门已在 `queryToTable(sql, sid)` 内部统一做。
- **教训**：旧接口在改造时容易留"还在用旧 API"的尾巴；以后每次加 sid 维度时，全链路 grep 一下老调用点。
