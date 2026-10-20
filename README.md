# AI 数据分析智能体（Spring Boot + Spring AI + DeepSeek）

上传一份 Excel/CSV（或连一个数据库），用自然语言提问，自动出结论 + 图表，并且**每个数字都能追溯到具体 SQL**。

不是「让大模型瞎猜数据」，而是：先做数据画像 → 按决策表选统计方法 → 调真实统计工具算 → 把检验结果、前提假设、样本量、局限一起交代清楚。

---

## 它解决什么问题

业务同事想看数据，通常有三条路：等数据分析师排期、自己拖 BI、或者问 ChatGPT 然后把表粘进去（数字经常是编的）。

这个工具走第四条：数据留在你自己的库里，模型只负责**决定查什么、用什么方法**，所有数值由工具真实计算。

---

## 核心能力

| 能力 | 说明 |
|---|---|
| 多格式导入 | CSV / XLSX / XLS / XLSM（带宏也行，只读数据不执行宏），中文表头原样保留 |
| 手写 SQL | 只读闸门拦截，只允许 `SELECT` |
| 连外部库 | MySQL / PostgreSQL / SQL Server / H2，连接后自动内省表结构并生成「数据字典」记忆文件 |
| 自动数据画像 | 每列类型、缺失率、min/max/mean/std、样例值，先给模型看形态再选方法 |
| 统计方法 | 描述统计(KS正态性/偏度峰度/IQR异常值)、t 检验(Welch + Cohen's d)、ANOVA、Pearson/Spearman(含 p 与 95%CI)、卡方、线性回归、帕累托/ABC、时间序列(环比/同比/移动平均/季节指数) |
| 一次多图 | 最多 4 张，智能体按问题分工自选图型（bar/line/pie/scatter/hist） |
| 口径可追溯 | 每张图下方可展开看它的 SQL、返回行数、是否截断 |
| 多轮对话 | 每个 session 独立记忆；`sessions` 按 sessionId 隔离 |
| 多人同时用 | 上传表按会话隔离（`UPLOADED_DATA_<sid>`），工具层通过 `ToolContext` 取会话 ID |
| 数据不丢 | H2 落盘（`./data`），服务重启后会话表自动从库里接回 |
| 业务口径规则 | 一份可编辑的 `business_rules.md`，每次问答强制注入（比如「活动只算进行中+报名成功待开始」） |
| 分析卡片 | 每次问答沉淀成 JSON，做月度复盘时可以翻回去复用 |
| 访问口令 | `/api/**` 校验 `X-Access-Code`，分享链接带 `?code=` 即可进入 |
| 自主规划 | 规划 → 执行 → 自检 → 带问题重做；工具调用有硬上限，超了强制收尾并在「局限」里列出没查的东西 |

---

## 关键设计（踩过的坑都在这）

1. **只读闸门在代码层，不在提示词里**
   `SqlGuard` 用 JSqlParser 解析，必须 `instanceof Select`，再叠一层关键字黑名单。靠 prompt 说"别删库"是不设防。

2. **pinned / scratch 两层会话数据**
   AI 在对话里会做探索性查询。早期每查一次就把结果写进"当前表"，用户上传的文件当场被示例表顶掉——表现就是"我明明传了文件，它却在分析内置库"。现在 `pinned`（用户显式动作：上传/手写 SQL）只被下一个用户动作替换，`scratch`（AI 探索）不影响分析主体。

3. **数据字典必须和数据源绑定**
   字典落盘持久化，而外部库连接只在内存里。曾经重启后把上次连的 Rfam 字典读回来注入提示词，AI 就按旧库的表名乱猜。现在字典记着自己的 `scopeUrl`，只有和当前数据源一致才注入。

4. **本地库落盘，不是内存库**
   `jdbc:h2:mem` 时每次重启上传的表全没了，用户看到的就是"上传无效"。改 `jdbc:h2:file:./data/analyst` 后重启也能自动接回会话表。

5. **中文列名要保留**
   物化到 H2 时如果把非 ASCII 洗成下划线，「地区」会变成 `__`，`SELECT 地区` 必然报列不存在。现在用 `identCore()` 保留中文 + 双引号标识符。

6. **缓存只由用户动作失效**
   原来把"当前表指纹"算进 cache key，AI 换个表指纹就漂移，缓存永不命中。现在只在上传 / 手写 SQL / 连库断库时 `invalidate(sid)`。

7. **自主规划不是"多想几轮"，而是"先立验收标准"**
   一次问答拆成四段：Planner 只出计划不查数（`deepseek-chat`，秒级）→ Executor 照计划带工具执行
   → SelfCheck 用正则查硬伤（缺段落、说"显著"却无 p 值）→ Verifier 用模型挑刺（数字自相矛盾、
   方法与数据不匹配、断言超出证据）。问题具体到"26% 应为 14.6%"，才让它重做；只说"重做"模型只会换个说法。
   约束三条：`max-replans` 限轮数、`max-tool-calls` 做在工具入口上、预算耗尽后强制无工具收尾。

8. **失败要说人话**
   前端统一 `readJson()`：响应不是 JSON（隧道 502 / 后端异常页）就翻译成「服务暂时不可用」；网关类错误自动重试一次，业务错误不重试（不重复计费）。

---

## 快速开始

```bash
git clone <this-repo> && cd <repo>
cp .env.example .env        # 填入 DEEPSEEK_API_KEY
bash restart.sh             # 编译 + 启动（需要 JDK 21；classpath 清单见 cp.txt 生成方式）
```

打开 <http://localhost:8080>，上传文件 → 提问。

> `cp.txt` 是本机 Maven 依赖的 classpath 清单，未入库。重新生成：
> `mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt`

### 对外分享（可选）

```bash
bash tunnel.sh              # Cloudflare Quick Tunnel，不限时，打印公网链接
bash tunnel.sh --restart    # 地址变了就重起（机器重启后）
```

链接形如 `https://xxx.trycloudflare.com/?code=你的口令`。**口令是唯一凭证，别往群里发。**

---

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/analyst/upload` | 上传文件（multipart，`file` + `sessionId`） |
| POST | `/api/analyst/sql` | 执行只读 SELECT，结果成为分析对象 |
| POST | `/api/analyst/ask` | 提问，返回 `answer` + `charts[]` + `dataReady` |
| GET | `/api/analyst/state` | 本会话有没有已载入的数据 |
| POST/GET/DELETE | `/api/analyst/db` | 连接 / 查询 / 断开外部库 |
| GET/POST | `/api/analyst/rules` | 读写业务口径规则 |
| GET | `/api/analyst/cards` | 分析卡片列表 |
| DELETE | `/api/analyst/chat` | 清空某会话的多轮记忆 |

---

## 配置（`application.yml` → `app.analyst`）

| 键 | 默认 | 说明 |
|---|---|---|
| `max-agent-steps` | 8 | 工具调用最大步数，防绕圈烧 token |
| `max-charts` | 4 | 单次问答最多出几张图 |
| `max-query-rows` | 200000 | 单次查询行数上限 |
| `cache-ttl-minutes` | 10 | 问答缓存有效期 |
| `profile-max-chars` | 12000 | 注入提示词的字典长度上限 |
| `profile-max-tables` | 20 | 建字典时最多收录多少张表 |
| `access-code` | 空 | 共享访问口令，空=不校验 |
| `planning` | true | 自主规划开关，关掉退回单轮工具调用 |
| `max-replans` | 1 | 被自检打回后最多重做几次 |
| `max-tool-calls` | 12 | 单次问答的工具调用上限（超了强制收尾） |
| `plan-model` | deepseek-chat | 规划/复核用的快模型 |
| `verifier` | true | 是否让模型复核挑刺（关掉只留代码级自检） |

---

## 目录结构

```
src/main/java/com/example/analyst/
  AnalystApplication.java   入口
  AnalystController.java    REST 接口
  AnalystAgent.java         智能体：拼提示词、调工具、解析多图、缓存
  StatsTools.java           统计工具（@Tool，全部真算）
  JdbcQueryTool.java        查库工具（只读）
  SqlGuard.java             只读闸门
  DataIngestion.java        文件/SQL → DataTable，物化进 H2
  DataProfiler.java         自动数据画像
  AnalysisSession.java      pinned/scratch 会话数据
  DbProfile.java            数据字典（记忆文件）
  BusinessRules.java        业务口径规则
  AnalysisCards.java        分析卡片沉淀
  AnalysisPlan.java         执行计划（目标/步骤/验收标准）
  ToolBudget.java           单次问答的工具调用预算（ThreadLocal）
  SelfCheck.java            代码级自检（不调模型）
  ExternalDb.java           外部数据源
src/main/resources/static/index.html   单页前端（ECharts）
```

---

## 已知限制

- 单进程内存态：多副本部署需要把 `AnalysisSession` / 缓存换成 Redis。
- 免费隧道域名不固定，机器重启后链接会变；要固定域名需用自己的域名做 Cloudflare named tunnel。
- 大表保护是"取回行数封顶"，不是下推计算——超大表建议先在库里聚合。
- 自主规划会把单问延迟从 30~60 秒拉到 1~2 分钟、token 约 2~3 倍；简单聚合问题可关掉 `planning`。
- 复核是模型判断，不是形式化证明；数字层面的硬校验（正则比对工具输出）尚未实现。
- 统计工具覆盖的是常见方法（t/ANOVA/相关/回归/卡方/时间序列），没有做生存分析、混合模型、因果推断。

---

## License

MIT
