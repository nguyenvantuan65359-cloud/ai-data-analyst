package com.example.analyst;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据分析专家智能体：LLM + 工具 + 多轮记忆。
 *
 * 流程：读数据字典（记忆文件）→ 读当前表画像 → 判断问题该用哪种统计方法（决策表）→
 * 调工具取数/计算 → 自选一个最合适的图型 → 按固定结构输出结论。
 *
 * 关键点（也是这个系统的价值所在）：
 * 1. 方法不是猜的，是先画像再按决策表选的；
 * 2. 图型由智能体根据问题自选，而不是让用户自己挑；
 * 3. 结论必须交代理论依据（原假设、检验统计量、前提假设）与局限；
 * 4. 最大工具调用次数受 max-agent-steps 限制，防无限循环烧 token。
 */
@Component
public class AnalystAgent {

    private final ChatClient chatClient;
    private final StatsTools statsTools;
    private final JdbcQueryTool jdbcTool;
    private final DataProfiler profiler;
    private final AnalysisSession session;
    private final ChatSessions sessions;
    private final DbProfile dbProfile;
    private final SqlGuard guard;
    private final DataIngestion ingestion;
    private final BusinessRules rules;
    private final AnalysisCards cards;
    private final ObjectMapper om = new ObjectMapper();

    @Value("${app.analyst.max-agent-steps:8}")
    private int maxSteps;

    /** 一次回答最多出几张图：多图能讲清一个问题时才多出，防止 LLM 无限刷图 */
    @Value("${app.analyst.max-charts:4}")
    private int maxCharts;

    /** 结果缓存：相同问题+数据态 N 分钟内直接复用，避免重复付费/查库 */
    @Value("${app.analyst.cache-ttl-minutes:10}")
    private long cacheTtlMinutes;

    /** key: sid + 数据态指纹 + 问题指纹；value: (结果, 缓存时间) */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.Map.Entry<AnalystResult, Long>> cache
            = new java.util.concurrent.ConcurrentHashMap<>();

    public AnalystAgent(ChatClient.Builder builder,
                        StatsTools statsTools,
                        JdbcQueryTool jdbcTool,
                        DataProfiler profiler,
                        AnalysisSession session,
                        ChatSessions sessions,
                        DbProfile dbProfile,
                        SqlGuard guard,
                        DataIngestion ingestion,
                        BusinessRules rules,
                        AnalysisCards cards) {
        this.chatClient = builder.build();
        this.statsTools = statsTools;
        this.jdbcTool = jdbcTool;
        this.profiler = profiler;
        this.session = session;
        this.sessions = sessions;
        this.dbProfile = dbProfile;
        this.guard = guard;
        this.ingestion = ingestion;
        this.rules = rules;
        this.cards = cards;
    }

    /** 多轮分析：sessionId 相同的请求共享对话记忆；同时也按 sid 隔离当前表与 uploaded_data */
    public AnalystResult analyze(String question, String sessionId) {
        // 1) 缓存：同问题+同数据态，TTL 内直接复用
        String sid = DataIngestion.safeSid(sessionId);
        String cacheKey = cacheKey(sid, question);
        java.util.Map.Entry<AnalystResult, Long> hit = cache.get(cacheKey);
        if (hit != null && System.currentTimeMillis() - hit.getValue() < cacheTtlMinutes * 60_000L) {
            return hit.getKey().withCached(true);
        }

        DataTable t = session.get(sessionId);
        // 不再因"session 没数据"拦截提问：本地示例表恒可查，连了外部库时
        // AI 靠 listTables/queryDatabase + 数据字典自主探索，用户可以直接问库的问题
        String system = buildSystem(t == null
                ? "（当前没有内存数据集。请先用 listTables 工具查看有哪些可查的表，再用 queryDatabase 取数。）"
                : profiler.profile(t), sessionId);
        ChatMemory memory = sessions.get(sessionId);

        String raw;
        try {
            raw = chatClient.prompt()
                    .system(system)
                    .user(question)
                    // 关键：必须显式把会话ID塞进 advisor 上下文，否则所有会话都写进默认会话，多轮就串了
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId)
                                    .advisors(MessageChatMemoryAdvisor.builder(memory).build()))
                    // 工具层也要知道会话 ID：统计分析工具与查库工具必须读写本会话的表，不能落到 default
                    .toolContext(Map.of("sessionId", sid))
                    .tools(statsTools, jdbcTool)
                    .call()
                    .content();
        } catch (Exception e) {
            // 多轮记忆与部分模型的历史消息格式可能冲突，失败时降级为单轮，保证可用
            raw = chatClient.prompt()
                    .system(system)
                    .user(question)
                    .toolContext(Map.of("sessionId", sid))
                    .tools(statsTools, jdbcTool)
                    .call()
                    .content();
        }
        AnalystResult result = parse(raw, sessionId);

        // 2) 落缓存 + 3) 沉淀为分析卡片（只要真有答案）
        cache.put(cacheKey, new java.util.AbstractMap.SimpleEntry<>(result, System.currentTimeMillis()));
        if (result.answer() != null && !result.answer().isBlank()) {
            cards.save(sessionId, question, result.answer(), result.charts());
        }
        return result;
    }

    /**
     * 缓存键：sid + 问题归一化。
     * 之前把"当前表行数"也算进指纹，结果 AI 在对话里做探索性查询会换掉会话表，
     * 指纹跟着对话漂移——缓存永远不命中。数据是否变过，只有用户动作说了算（见 invalidate）。
     */
    private String cacheKey(String sid, String question) {
        return sid + "|" + question.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    /**
     * 用户换了数据就失效——只在真正的变更点调用（上传文件 / 手写 SQL / 连库断库）。
     * 注意：AI 在对话里的探索性查询不算数据变更，那是对话内部行为。
     */
    public void invalidate(String sessionId) {
        String prefix = DataIngestion.safeSid(sessionId) + "|";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** 全局失效：外部数据源变了（谁知道哪些会话问过它），一律清空 */
    public void invalidateAll() { cache.clear(); }

    private String buildSystem(String profile, String sessionId) {
        // 只注入"描述的就是当前数据源"的字典：外部库断开/重启后，磁盘上的旧字典不许再出现，
        // 否则会出现"用户上传了文件，AI 却按上次那个外部库的表名去猜"这种错。
        String dict = dbProfile.active();
        String sid = DataIngestion.safeSid(sessionId);
        String sidNote = "\n\n[本会话隔离] sessionId=" + (sessionId == null ? "(无)" : sessionId)
                + "；本会话上传的文件物化在表 `uploaded_data_" + sid + "`，"
                + "你写 SQL 时用 `uploaded_data` 字面也会被自动改写到该表，无需手动拼。";
        String dictBlock = (dict == null || dict.isBlank())
                ? "（当前没有可用的数据字典。要看库里有什么表，调用 listTables 工具。）"
                : dict;
        dictBlock = dictBlock + sidNote;

        // 用户显式动作（上传文件 / 手写 SQL）钉住的分析对象：优先级高于一切字典与示例表
        String pinnedBlock;
        if (session.hasPinned(sessionId)) {
            DataTable cur = session.resolve(sessionId);
            pinnedBlock = "**【锁定】本会话当前分析对象 = " + session.sourceName(sessionId)
                    + "（" + (cur == null ? "?" : cur.rowCount()) + " 行）**。\n"
                    + "用户是上传了文件来问问题的，一切分析只能基于这张表：\n"
                    + "- 统计工具已自动作用于它，不要先入为主去查别的表；\n"
                    + "- 禁止查询 sales 等示例表，除非用户明确要求换表；\n"
                    + "- 若数据字典里有与本表无关的外部库表，**全部忽略**，不要引用、不要查。";
        } else {
            pinnedBlock = "（本会话还没有锁定分析对象：用户未上传文件、也未执行手写 SQL。"
                    + "此时才可以用 listTables 探索可用表。）\n"
                    + "**如果探索后发现只有演示表 sales 可查，你的回答第一句必须明确写出："
                    + "「本次分析基于演示数据 sales，不是你上传的文件」**，并提醒用户先上传文件或执行 SQL。"
                    + "绝不能让用户误以为这就是他自己那份数据。";
        }

        // 业务口径规则：人工维护的"哪些数据算进来"，每次问答都带，不给临场发挥的机会
        String rulesBlock = rules.configured()
                ? rules.current()
                : "（未配置。若分析中涉及状态筛选、剔除规则、口径边界，请在「局限与提醒」里明确写出你采用了什么口径。）";

        return """
                你是资深数据分析专家，兼具统计学家与业务顾问的视角。
                你只能对数据库做只读查询（仅 SELECT，禁止增删改）。

                ─────────── 业务口径规则（人工维护，优先级最高）───────────
                {{RULES}}
                当口径规则与分析对象的表结构有关时，必须先把规则翻译成 SQL 的 WHERE 条件再查数。

                ─────────── 本次分析对象 ───────────
                {{PINNED}}

                ─────────── 数据字典（记忆文件）───────────
                {{DICT}}

                ─────────── 当前数据表画像 ───────────
                {{PROFILE}}

                ─────────── 第一步：判断该用哪个数据模型 ───────────
                收到问题后，先判断它属于哪一类，再按表选方法与图型（不要跳过这一步直接查数）：

                | 问题类型 | 调用的工具 | 推荐图型 |
                |---|---|---|
                | 一组数据长什么样、集中/离散程度 | describe（含缺失率、异常值、正态性） | hist |
                | 两组均值是否不同 | tTest（Welch，含 Cohen's d、方差比） | bar |
                | 三组及以上均值是否不同 | anova（单因素方差分析：F、p、各组均值） | bar |
                | 两个数值变量线性关系 | correlation（Pearson：r、p、95%CI） | scatter |
                | 单调但非线性、有极端值 | spearman（秩相关 ρ、p） | scatter |
                | 用一个变量解释另一个 | regression（斜率、截距、R²、斜率CI、p） | scatter |
                | 随时间怎么变、淡旺季 | timeSeries（环比/同比/移动平均/趋势/季节指数） | line |
                | 构成与占比、抓重点少数 | pareto（累计 80%）/ abc（A/B/C 分类） | pie 或 bar |
                | 各类别分布比例是否不同 | chiSquare（χ²、p、期望频数校验） | bar |

                ─────────── 工具使用铁律（不许违反）───────────
                1. **所有统计数字必须来自工具返回值**：p 值、F 值、χ²、r、斜率、均值、异常值数，
                   一律只能引用工具输出。禁止心算、禁止估算、禁止"根据经验判断"。
                2. 工具返回里的「[数据质量]」与「前提」「正态性参考」「最小期望频数」等提示必须写进结论，
                   尤其是**有效样本 n 与缺失率**——这是结论可信度的一部分。
                3. 表里列的工具都真实存在，直接用；**不要编造不存在的工具**。
                4. 工具报错或返回"样本不足"时，如实告诉用户缺什么，不要改口给出结论。
                5. 需要用 SQL 聚合时请先确认列名以防万一；列名找不到时工具会给出可用列清单。

                ─────────── 第二步：图型选择规则 ───────────
                - hist：看单个数值列的分布。sql 只返回这一列，前端自动分箱。
                - bar ：分类维度的对比或排名。sql 返回 X=类目、Y=数值，请自己 GROUP BY 聚合。
                - line：时间或有序数值的趋势。sql 返回 X=时间点、Y=数值，请自己 ORDER BY X。
                - scatter：两列数值的关系。sql 返回 X、Y 两列，**不要聚合**。
                - pie：占比且类目不超过 8 个。sql 返回 X=类目、Y=数值。

                ─────────── 第三步：输出格式（严格遵守）───────────
                按下面六个小标题依次输出，缺一不可：

                ## 结论
                （先给答案，必须带具体数字，不要铺垫）

                ## 方法选择依据
                （为什么用这个方法，为什么不用别的；一句话说明数据形态与方法前提的匹配）

                ## 统计学理论
                - 方法名：
                - 原假设 H0 / 备择假设 H1：
                - 检验统计量与判断标准（如 p<0.05）：
                - 前提假设是否满足（独立性/正态性/方差齐性/样本量）：

                ## 图表解读
                （配合所选图型，说清图上能看见的规律）

                ## 业务启示
                （可以据此做什么决策，2-3 条）

                ## 局限与提醒
                （样本量、混杂因素、相关性≠因果等）

                ─────────── 第四步：输出图表指令 ───────────
                正文「## 局限与提醒」之后，另起一行开始输出图表指令（不要在正文里解释它）。

                一个问题往往需要**多张图**才能讲清，你可以输出 1~{{MAXCHARTS}} 个图表块，
                **每个图表单独一个 ```chart 代码块**，依次排列。典型组合举例：
                - 看趋势：line（月度走势）+ bar（月度排名）+ hist（单月金额分布）
                - 看对比：bar（各组均值）+ scatter（两个变量关系）
                - 看构成：pie（占比）+ bar（帕累托累计）

                每个块的格式（一行一个 JSON，不要换行、不要注释、不要尾逗号）：
                ```chart
                {"type":"line","title":"月度销售额走势","x":"MONTH","y":"TOTAL","sql":"SELECT month, SUM(sales_amount) AS total FROM sales GROUP BY month ORDER BY month"}
                ```
                ```chart
                {"type":"bar","title":"各地区销售额排名","x":"REGION","y":"TOTAL","sql":"SELECT region, SUM(sales_amount) AS total FROM sales GROUP BY region ORDER BY total DESC"}
                ```

                规则：
                - 图与图之间要有分工，不要出两张含义重复的图；一张够讲清就只出一张；
                - sql 只能是 SELECT，且不要带分号；
                - 除 hist 外，sql 必须返回两列（X、Y）；
                - 表名列名以数据字典/画像为准，不要臆造；
                - 这个问题确实不适合画图时，输出单个块 {"type":"none"}；
                - 数据不足以回答时，直说缺什么，绝不编造数字。
                """.replace("{{RULES}}", rulesBlock)
                   .replace("{{PINNED}}", pinnedBlock)
                   .replace("{{DICT}}", dictBlock)
                   .replace("{{PROFILE}}", profile)
                   .replace("{{MAXCHARTS}}", String.valueOf(maxCharts));
    }

    private static final Pattern CHART_BLOCK =
            Pattern.compile("```chart\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_BLOCK =
            Pattern.compile("```json\\s*(\\{[\\s\\S]*?\\})\\s*```", Pattern.CASE_INSENSITIVE);

    /**
     * 从回答里拆出**全部**图表指令；后端逐个过只读闸门并执行取数，把数据一并返回前端渲染。
     * 一张图讲不清的问题，智能体可以出多张——这里负责全部兑现，单张失败不影响其余。
     */
    private AnalystResult parse(String raw, String sessionId) {
        String text = raw == null ? "" : raw;

        List<String> jsons = new ArrayList<>();
        Matcher m = CHART_BLOCK.matcher(text);
        while (m.find()) jsons.addAll(splitJsonObjects(m.group(1)));
        if (jsons.isEmpty()) {
            Matcher m2 = JSON_BLOCK.matcher(text);
            while (m2.find()) {
                if (m2.group(1).contains("\"type\"")) jsons.addAll(splitJsonObjects(m2.group(1)));
            }
        }
        String answer = text.replaceAll("(?i)```chart[\\s\\S]*?```", "").trim();
        if (jsons.isEmpty()) return new AnalystResult(answer, null, null, List.of(), false);

        List<Map<String, Object>> charts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String j : jsons) {
            if (charts.size() >= maxCharts) break;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> spec = om.readValue(j, Map.class);
                ChartSpec cs = ChartSpec.from(spec);
                if ("none".equals(cs.type) || cs.sql == null || cs.sql.isBlank()) continue;

                // 取数走 queryToTable(sql, sessionId)：内部统一过闸门（含跨会话表拦截）+ 重写 + 路由 + 行数上限
                DataTable d = ingestion.queryToTable(cs.sql, sessionId);
                int cap = Math.min(d.rowCount(), 1000);
                List<List<Object>> rows = new ArrayList<>();
                for (int r = 0; r < cap; r++) rows.add(d.rows.get(r));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("columns", d.columns);
                data.put("rows", rows);
                data.put("returnedRows", d.rowCount());     // 查询真实返回行数
                data.put("truncated", d.rowCount() > cap);  // 画图是否只取前 1000 行（口径必须可见）

                Map<String, Object> item = cs.toMap();
                item.put("data", data);
                charts.add(item);
            } catch (SecurityException | IllegalArgumentException e) {
                problems.add("一张图被只读闸门拒绝：" + e.getMessage());
            } catch (Exception e) {
                problems.add("一张图取数失败：" + e.getMessage());
            }
        }

        if (charts.isEmpty()) {
            String tail = problems.isEmpty() ? "" : "\n\n（" + String.join("；", problems) + "）";
            return new AnalystResult(answer + tail, null, null, List.of(), false);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> firstData = (Map<String, Object>) charts.get(0).get("data");
        String tail = problems.isEmpty() ? ""
                : "\n\n（其余 " + problems.size() + " 张图未生成：" + String.join("；", problems) + "）";
        return new AnalystResult(answer + tail, charts.get(0), firstData, charts, false);
    }

    /** 一个代码块里可能有多个 JSON 对象（LLM 偶尔会塞在一起），按顶层 {} 逐个切出来 */
    private static List<String> splitJsonObjects(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inStr = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) { out.add(s.substring(start, i + 1)); start = -1; }
            }
        }
        return out;
    }
}
