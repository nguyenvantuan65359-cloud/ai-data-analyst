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

## 数据体检（主动发现，不等你提问）

`POST /api/analyst/scan` — 上传数据后自动跑，**毫秒级，不调模型**。

判据四要素，缺一不可：

> 偏离基线 × 超过噪声 × 有业务体量 × 样本够

| 模式 | 触发条件 | 能说什么 | 不能说什么 |
|---|---|---|---|
| A 时间模式 | 有可解构的时间列且 ≥5 期 | 「连续三期走低 −18.7%，剥离大盘后仍有 −16k」 | — |
| B 横截面模式 | 没有时间列 | 「单位产出是同类期望的 1.57 倍」 | **绝不可以说涨跌** |

实现要点：

- **时间解构**先跑：`202609` / `2026-09` / `9月` / `26W38` / `Q3` / `第12期` 都能归一成期序号；解不出来才降级成横截面。
- **四重约束**识别时间列：可解析成严格时间写法、期数 ≤ 行数/3、期值近似连续、**期均行数 ≥1.5**。第四条是踩了两次坑才补的——金额/订单数这类"逐行递增的数值列"排序后相邻差恰好为 1，会被误认成时间轴，整个分析跑错轴还看不出来。
- **稳健 z** 用中位数 + MAD，不用均值 + 标准差：一个异常点就会污染基线，然后它自己不再是异常。
- **3 期对 3 期累计**单独算一遍：真实业务里最常见的是"连续三个月慢慢掉"，只看最后一期根本进不了前几。
- **三道闸门**：分母过小/骤降、量价严重背离（金额 ×3 而数量只剩 2，多为数据断裂）、体量贡献 <1%。拦下的条目仍会列出来并写明原因。
- **反向检验**只用内部数据：同期同父级切片是否同向？全变 = 共性；只有它变 = 更可能与本切片自身因素有关。
- **样本不足影响排名而不只是提醒**：交叉切片每格每期不到 2 行时降权 0.5，避免"单行对单行"的波动挤掉可靠的单维结论。
- 报告第一行必须写明模式。**把横截面说成「上涨」是致命误导**，所以它宁可说"不能判断涨跌"。

### 模型解读：只翻译，不算数

`POST /api/analyst/scan` 只出数字（12ms）；`POST /api/analyst/scan/narrate` 才调模型（3~8s）。
拆开是因为**不该让一段文字把数字卡住**——页面先出发现，解读后补。

护栏靠结构，不只靠提示词：

| 护栏 | 做法 |
|---|---|
| 它没有机会自己算 | 只喂已算好的结论（切片、环比、z、O/E、反向检验），**拿不到原始数据** |
| 它不能编数字 | 解读文本里的数字必须能在报告里找到，找不到整条丢弃 |
| 它不能编因果 | 提示词禁写"因为/导致"，只能写"需核对…" |
| 横截面模式不许出现趋势词 | 上涨/下滑/增长/环比…一个都不许，**含"不能读作增长"这类否定用法**（读者只会记住"增长"两个字）；出现即丢 |

丢弃的条目会回报 `dropped` 数量，前端显示"有 N 条解读引用了报告里没有的数字，已丢弃"。**宁可少说，不说没出处的数。**

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

7. **数字出处留痕：这个数到底从哪来的**
   算术复算只能判断"能否由文中数字复现"，模型可以编一个自洽但错的算式蒙混过去。
   所以工具调用统一包一层 `TracingToolCallback`（在回调层记，12 个工具一个都漏不掉），
   把每次工具返回、图表取数结果、数据画像都记进 `ToolTrace`；`GroundingCheck` 再要求
   答案里每个三位数以上的数字都能在这些素材里找到（允许四舍五入），
   派生值（两数之差/和）交给 ArithCheck 管，不算"凭空出现"。
   默认 `grounding: warn`——先只提示不重做，观察误报率；确认稳定后再改 `blocking`。

8. **算术复算：专治"方向对、数字错"**
   实测最危险的错不是方法选错，而是模型**心算派生指标**：两个真实数字都在正文里，写出来的差值和百分比却是错的
   （18,002 该是 20,358；15.5% 没交代分母）。`ArithCheck` 纯代码复算：把答案里的数字全收进来当素材，
   检查"多/少了 X 元"能否由两数相减复现、"比…增长 Y%"能否按两种分母复现；复现不出就打回。
   同时提示词铁律禁止心算：差值/占比/环比一律先用 SQL 算再引用。
   限制：两种分母都接受——分母歧义由提示词要求「写出算式和分母」来解决，代码只兜"算不出来"。

9. **复核意见要分级，否则每次都白跑一轮**
   `Verifier` 输出 `blocking`（数字矛盾、把描述说成因果、用了不存在的字段）和 `suggestion`
   （该做 Levene/Shapiro 而不是看方差比、该补置信区间）。只有 blocking 触发重做，
   suggestion 直接附在答案末尾。改之前每个问题都被"方法学不够严谨"打回、稳定跑 2 轮；
   改之后同类问题 1 轮出结果（24.7 秒，4 张图），且方法学意见仍然可见。
   另加两道防御：自己都说"一致/无矛盾"的条目直接丢弃；方法学类意见即使被标成 blocking 也自动降级。

10. **自主规划不是"多想几轮"，而是"先立验收标准"**
   一次问答拆成四段：Planner 只出计划不查数（`deepseek-chat`，秒级）→ Executor 照计划带工具执行
   → SelfCheck 用正则查硬伤（缺段落、说"显著"却无 p 值）→ Verifier 用模型挑刺（数字自相矛盾、
   方法与数据不匹配、断言超出证据）。问题具体到"26% 应为 14.6%"，才让它重做；只说"重做"模型只会换个说法。
   约束三条：`max-replans` 限轮数、`max-tool-calls` 做在工具入口上、预算耗尽后强制无工具收尾。

11. **失败要说人话**
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
| POST | `/api/analyst/scan` | **数据体检**：主动扫描异常与增长点，纯 Java 计算、不调模型，毫秒级 |
| POST | `/api/analyst/scan/narrate` | 体检发现的模型解读：只翻译已算好的结果，引用报告外数字的条目自动丢弃 |
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
| `max-tool-calls` | 50 | 单次问答的工具调用上限（超了强制收尾） |
| `plan-model` | deepseek-chat | 规划/复核用的快模型 |
| `verifier` | true | 是否让模型复核挑刺（关掉只留代码级自检） |
| `arith-check` | true | 差值/百分比能否用正文数字复现 |
| `grounding` | warn | 数字出处校验：off=不查 / warn=只提示 / blocking=打回 |

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
  SchemaSkeleton.java       骨架识别：时间解构 / 度量 / 维度（四重约束）
  DiscoveryEngine.java      体检引擎：双模式扫描 + 判据 + 反向检验
  ScanReport.java           体检结果（发现 / 拦截 / 质量提醒）
  FindingNarrator.java      体检解读（只翻译不算数，带数字出处校验）
  ToolBudget.java           单次问答的工具调用预算（ThreadLocal）
  SelfCheck.java            代码级自检（不调模型）
  ArithCheck.java           算术复算：差值/百分比能否用文中数字复现
  ToolTrace.java            工具/图表/画像的数字素材留痕
  TracingToolCallback.java  工具回调包装器（在必经之路记录，漏不掉）
  GroundingCheck.java       数字出处校验
  ExternalDb.java           外部数据源
src/main/resources/static/index.html   单页前端（ECharts）
```

---

## 已知限制

- 单进程内存态：多副本部署需要把 `AnalysisSession` / 缓存换成 Redis。
- 免费隧道域名不固定，机器重启后链接会变；要固定域名需用自己的域名做 Cloudflare named tunnel。
- 大表保护是"取回行数封顶"，不是下推计算——超大表建议先在库里聚合。
- 自主规划会把单问延迟从 30~60 秒拉到 1~2 分钟、token 约 2~3 倍；简单聚合问题可关掉 `planning`。
- 复核是模型判断，不是形式化证明。
- 数字出处校验默认只告警不拦截（`grounding: warn`）：素材池覆盖工具返回、图表数据和画像，
  但派生值靠"两数之差/和"判断，会给编造的算式留口子；要严格拦截需把 SQL 结果做逐值指纹。
- 统计工具覆盖的是常见方法（t/ANOVA/相关/回归/卡方/时间序列），没有做生存分析、混合模型、因果推断。

---

## License

MIT
