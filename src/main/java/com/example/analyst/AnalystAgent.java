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
    private final ToolBudget toolBudget;
    private final ObjectMapper om = new ObjectMapper();

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnalystAgent.class);

    /**
     * 自主规划开关：开=规划→执行→自检→（带问题）重做；关=老的单轮工具调用。
     * 关掉是有用的：reasoner 很慢，赶时间或只问一个简单聚合时，多一轮规划纯属浪费。
     */
    @Value("${app.analyst.planning:true}")
    private boolean planning;

    /** 最多重做几次（不含首次执行）：1 表示总共最多执行 2 轮 */
    @Value("${app.analyst.max-replans:1}")
    private int maxReplans;

    /** 单次问答允许的工具调用次数（做在工具入口上，超了强制收尾） */
    @Value("${app.analyst.max-tool-calls:12}")
    private int maxToolCalls;

    /** 规划与复核用的快模型：这两步不需要深度推理，用 reasoner 纯等 */
    @Value("${app.analyst.plan-model:deepseek-chat}")
    private String planModel;

    /** 是否启用模型复核（挑刺）。关掉只保留代码级自检 */
    @Value("${app.analyst.verifier:true}")
    private boolean verifier;

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
                        AnalysisCards cards,
                        ToolBudget toolBudget) {
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
        this.toolBudget = toolBudget;
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

        AnalystResult result;
        toolBudget.begin(maxToolCalls);
        try {
            result = planning
                    ? plannedAnalysis(question, sessionId, sid)
                    : singleShot(question, sessionId, sid, null, null);
        } finally {
            toolBudget.end();   // ThreadLocal 必须清，否则下一个请求会继承上次的计数
        }

        // 2) 落缓存 + 3) 沉淀为分析卡片（只要真有答案）
        cache.put(cacheKey, new java.util.AbstractMap.SimpleEntry<>(result, System.currentTimeMillis()));
        if (result.answer() != null && !result.answer().isBlank()) {
            cards.save(sessionId, question, result.answer(), result.charts());
        }
        return result;
    }

    /**
     * 自主规划主循环：规划 → 执行 → 自检 → 带着具体问题重做。
     *
     * 三个关键约束（缺一个这套东西就会失控）：
     * 1. 轮数上限 maxReplans，不是"直到满意为止"；
     * 2. 工具调用次数上限做在 ToolBudget 上，超了强制无工具收尾；
     * 3. 自检必须给出**具体哪条不对**，只说"重做"模型只会换个说法糊过去。
     */
    private AnalystResult plannedAnalysis(String question, String sessionId, String sid) {
        AnalysisPlan plan = makePlan(question, sessionId);
        log.info("规划 session={} 步数={}：{}", sid,
                plan.steps() == null ? 0 : plan.steps().size(), plan.render().replace('\n', ' '));

        AnalystResult best = null;
        List<String> issues = List.of();
        int attempts = 0;

        for (int round = 0; round <= maxReplans; round++) {
            attempts++;
            // 预算耗尽就不带工具了，否则每一轮都只会再撞一次上限
            AnalystResult r = toolBudget.exhausted()
                    ? singleShot(question, sessionId, sid,
                        "工具预算已用完，请仅凭已取得的结果作答。" + renderIssues(issues), null)
                    : singleShot(question, sessionId, sid, renderIssues(issues), plan);
            best = r;

            List<String> found = new java.util.ArrayList<>(SelfCheck.check(r));
            // 最后一轮不再复核：已经没有重做的机会，多花一次调用没意义
            if (verifier && round < maxReplans) {
                found.addAll(verify(question, plan, r));
            }
            if (found.isEmpty()) {
                issues = List.of();
                break;
            }
            issues = found;
            log.info("自检第 {} 轮未通过 session={}：{}", attempts, sid, found);
        }

        AnalystResult out = best == null
                ? AnalystResult.textOnly("分析没有产出结果，请重试或换一种问法。")
                : best.withPlan(plan.lines(), attempts);

        if (!issues.isEmpty()) {
            out = appendNote(out, "\n\n---\n**自检提示**：以下问题在重做后仍未完全解决，请谨慎采信结论：\n- "
                    + String.join("\n- ", issues));
        }
        return out;
    }

    private static String renderIssues(List<String> issues) {
        if (issues == null || issues.isEmpty()) return null;
        return "上一轮自检发现这些问题，必须先修正再作答：\n- " + String.join("\n- ", issues);
    }

    private AnalystResult appendNote(AnalystResult r, String note) {
        return new AnalystResult((r.answer() == null ? "" : r.answer()) + note,
                r.chart(), r.chartData(), r.charts(), r.cached(), r.plan(), r.attempts());
    }

    /** 单轮执行：拼系统提示词（含计划与被退回的原因）→ 带工具调模型 → 解析图表 */
    private AnalystResult singleShot(String question, String sessionId, String sid,
                                     String feedback, AnalysisPlan plan) {
        DataTable t = session.get(sessionId);
        String profile = t == null
                ? "（当前没有内存数据集。请先用 listTables 工具查看有哪些可查的表，再用 queryDatabase 取数。）"
                : profiler.profile(t);

        StringBuilder sys = new StringBuilder(buildSystem(profile, sessionId));
        if (plan != null) {
            sys.append("\n\n─────────── 本次分析计划（先照这个走）───────────\n")
               .append(plan.render())
               .append("\n执行中若发现某步前提不成立（比如缺列、样本不足），可以跳过并在「局限与提醒」里写清原因，")
               .append("但不要悄悄换个问题去答。");
        }
        if (feedback != null && !feedback.isBlank()) {
            sys.append("\n\n─────────── 上一轮被打回的原因（必须逐条解决）───────────\n").append(feedback);
        }

        String raw = call(sys.toString(), question, sessionId, sid, true);
        return parse(raw, sessionId);
    }

    /**
     * 带工具/带记忆的一次调用；异常时降级为单轮（部分模型的历史消息格式与 advisor 冲突）。
     * 工具调用次数由 ToolBudget 在工具入口兜底，这里不重复计数。
     */
    private String call(String system, String user, String sessionId, String sid, boolean withTools) {
        ChatMemory memory = sessions.get(sessionId);
        try {
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt().system(system).user(user);
            if (withTools) spec = spec.toolContext(Map.of("sessionId", sid)).tools(statsTools, jdbcTool);
            // 关键：必须显式把会话ID塞进 advisor 上下文，否则所有会话都写进默认会话，多轮就串了
            return spec.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId)
                            .advisors(MessageChatMemoryAdvisor.builder(memory).build()))
                    .call().content();
        } catch (Exception e) {
            log.warn("带记忆调用失败，降级单轮：{}", e.toString());
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt().system(system).user(user);
            if (withTools) spec = spec.toolContext(Map.of("sessionId", sid)).tools(statsTools, jdbcTool);
            return spec.call().content();
        }
    }

    /** 快模型调用（规划/复核用）：不挂工具、不挂记忆，温度 0 求稳定 */
    private String callCheap(String system, String user) {
        try {
            return chatClient.prompt().system(system).user(user)
                    // Spring AI 2.0 的 options 接的是 Builder，不是 Options 实例
                    .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                            .model(planModel).temperature(0.0))
                    .call().content();
        } catch (Exception e) {
            log.warn("快模型（{}）调用失败，退回主模型：{}", planModel, e.toString());
            return chatClient.prompt().system(system).user(user).call().content();
        }
    }

    /** 第一步：把问题拆成可执行、可验收的步骤（只出计划，不许查数） */
    private AnalysisPlan makePlan(String question, String sessionId) {
        DataTable t = session.get(sessionId);
        String profile = t == null ? "（本会话还没有数据表）" : profiler.profile(t);

        String system = """
                你是数据分析任务的规划师。你的唯一产出是**执行计划**，不是答案，也不要写 SQL。

                要求：
                1. 先判断问题属于哪一类：描述分布 / 两组比较 / 多组比较 / 相关与回归 / 构成与集中度 / 时间趋势 / 数据质量盘点；
                2. 拆成 1~5 步，每步都要能独立验收（做完能判断"这步成了没有"）；
                3. 每步写明：task（做什么）、method（用哪个统计方法或取数方式）、expects（预期拿到什么）；
                4. 第 1 步通常是"确认可用列与数据质量"，最后一步通常是"给出结论与边界"；
                5. 只能基于给出的真实列名规划，**列不存在就不要规划依赖它的步骤**；
                6. 输出严格 JSON，不要 markdown 代码块，不要解释文字。

                JSON 结构：
                {"goal":"一句话目标","steps":[{"id":1,"task":"...","method":"...","expects":"..."}],"acceptance":"什么算完成"}

                可用方法（工具）：describe 描述统计、tTest 两组均值、anova 多组均值、correlation Pearson、
                spearman 秩相关、regression 回归、chiSquare 卡方、pareto 帕累托、abc ABC 分类、timeSeries 时间序列、
                queryDatabase 只读 SQL 取数、listTables 列出可用表。
                """;

        String user = "用户问题：" + question + "\n\n当前数据表画像：\n" + profile
                + (rules.configured() ? "\n\n业务口径规则（必须体现在计划里）：\n" + rules.current() : "");

        try {
            String json = callCheap(system, user);
            return parsePlan(json, question);
        } catch (Exception e) {
            log.warn("规划失败，退化为单步计划：{}", e.toString());
            return AnalysisPlan.fallback(question);
        }
    }

    private AnalysisPlan parsePlan(String raw, String question) {
        String json = extractJson(raw);
        if (json == null) return AnalysisPlan.fallback(question);
        try {
            com.fasterxml.jackson.databind.JsonNode n = om.readTree(json);
            String goal = n.path("goal").asText(question);
            String acceptance = n.path("acceptance").asText("");
            List<AnalysisPlan.Step> steps = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode s : n.path("steps")) {
                steps.add(new AnalysisPlan.Step(
                        s.path("id").asInt(steps.size() + 1),
                        s.path("task").asText(""),
                        s.path("method").asText(""),
                        s.path("expects").asText("")));
            }
            if (steps.isEmpty()) return AnalysisPlan.fallback(question);
            return new AnalysisPlan(goal, steps, acceptance);
        } catch (Exception e) {
            return AnalysisPlan.fallback(question);
        }
    }

    /** 第二步之后：复核员挑刺。只报真问题，宁可少报也不要凑数——凑数会让重做变成瞎改 */
    private List<String> verify(String question, AnalysisPlan plan, AnalystResult r) {
        String charts = "（无图）";
        if (r.charts() != null && !r.charts().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> c : r.charts()) {
                sb.append("- ").append(c.get("type")).append("｜").append(c.get("title"))
                  .append("｜SQL: ").append(c.get("sql")).append("\n");
            }
            charts = sb.toString();
        }
        String system = """
                你是最挑剔的数据分析复核员。你要检查下面这份分析是否站得住，只报**真实存在**的问题。

                检查项（按这个顺序看，没有问题就跳过）：
                1. 数字自相矛盾：前后两个数字对不上，或分组之和与总数不符；
                2. 方法与数据不匹配：用 t 检验比三组以上、样本 n<3 还讲显著性、对类别列做回归等；
                3. 断言超出证据：把相关说成因果、把描述性排名说成"显著更高"、给出数据里根本没有的字段；
                4. 口径缺失：没交代筛选条件、样本量、缺失值处理方式，导致结论无法复现。

                输出严格 JSON，不要代码块：{"issues":["具体指出哪里不对、应该改成什么"]}
                没有任何问题时输出 {"issues":[]}。**不要提出措辞、排版、语气类的意见，那不是问题。**
                """;
        String user = "用户问题：" + question
                + "\n\n原计划：\n" + plan.render()
                + "\n\n分析正文：\n" + (r.answer() == null ? "" : r.answer())
                + "\n\n图表与取数 SQL：\n" + charts;

        try {
            String json = extractJson(callCheap(system, user));
            if (json == null) return List.of();
            com.fasterxml.jackson.databind.JsonNode n = om.readTree(json);
            List<String> out = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode i : n.path("issues")) {
                String s = i.asText("");
                if (!s.isBlank()) out.add(s);
            }
            return out.size() > 4 ? out.subList(0, 4) : out;
        } catch (Exception e) {
            log.warn("复核调用失败，跳过本轮复核：{}", e.toString());
            return List.of();
        }
    }

    /** 从模型输出里抠出第一个 JSON 对象（模型常加 ```json 或前后废话） */
    private static String extractJson(String raw) {
        if (raw == null) return null;
        int b = raw.indexOf('{');
        if (b < 0) return null;
        int depth = 0;
        boolean inStr = false;
        for (int i = b; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return raw.substring(b, i + 1);
            }
        }
        return null;
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
        if (jsons.isEmpty()) return new AnalystResult(answer, null, null, List.of(), false, List.of(), 0);

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
            return new AnalystResult(answer + tail, null, null, List.of(), false, List.of(), 0);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> firstData = (Map<String, Object>) charts.get(0).get("data");
        String tail = problems.isEmpty() ? ""
                : "\n\n（其余 " + problems.size() + " 张图未生成：" + String.join("；", problems) + "）";
        return new AnalystResult(answer + tail, charts.get(0), firstData, charts, false, List.of(), 0);
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
