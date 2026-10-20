package com.example.analyst;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analyst")
public class AnalystController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnalystController.class);

    private final DataIngestion ingestion;
    private final AnalysisSession session;
    private final AnalystAgent agent;
    private final SqlGuard guard;
    private final ExternalDb externalDb;
    private final DbProfile dbProfile;
    private final ChatSessions chatSessions;
    private final BusinessRules rules;
    private final AnalysisCards cards;

    public AnalystController(DataIngestion ingestion, AnalysisSession session,
                            AnalystAgent agent, SqlGuard guard, ExternalDb externalDb,
                            DbProfile dbProfile, ChatSessions chatSessions,
                            BusinessRules rules, AnalysisCards cards) {
        this.ingestion = ingestion;
        this.session = session;
        this.agent = agent;
        this.guard = guard;
        this.externalDb = externalDb;
        this.dbProfile = dbProfile;
        this.chatSessions = chatSessions;
        this.rules = rules;
        this.cards = cards;
    }

    // ---------- 外部数据源配置 ----------

    /** 连接/测试外部数据库（MySQL / PostgreSQL / SQL Server / H2），成功后保存并返回表清单 */
    @PostMapping("/db")
    public Map<String, Object> configDb(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("请填写 JDBC URL");
        try {
            List<String> tables = externalDb.connect(url.trim(), username, password);
            // 连接成功：立刻内省全库结构并让 AI 写出“每张表存什么、能做什么分析”的记忆文件
            String dict = dbProfile.build();
            agent.invalidate(body.get("sessionId"));   // 换了数据源：旧缓存一律失效
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "ok");
            m.put("url", url.trim());
            m.put("tables", tables);
            m.put("profile", dict);
            m.put("note", "已连接，并已生成数据字典（记忆文件）。上传文件仍在本地表 uploaded_data；其余查询走该外部库（仅 SELECT）。");
            return m;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            throw new IllegalArgumentException("连接失败：" + (msg.length() > 300 ? msg.substring(0, 300) : msg));
        }
    }

    @GetMapping("/db")
    public Map<String, Object> getDb() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", externalDb.isConfigured());
        m.put("url", externalDb.url());
        m.put("tables", externalDb.tables());
        m.put("profile", dbProfile.current());
        return m;
    }

    /** 断开外部库，恢复为只查本地 H2 */
    @DeleteMapping("/db")
    public Map<String, Object> dropDb() {
        externalDb.clear();
        dbProfile.clear();
        cacheClearAll();
        return Map.of("status", "ok", "configured", false);
    }

    /** 断开数据源时缓存整体失效：外部库是全局共享的，无法按会话粒度区分 */
    private void cacheClearAll() { agent.invalidateAll(); }

    /** 重建数据字典（记忆文件）；人工修订区会被保留 */
    @PostMapping("/db/profile")
    public Map<String, String> rebuildProfile() {
        return Map.of("profile", dbProfile.build());
    }

    /** 保存字典的人工修订区（重建时不被覆盖） */
    @PostMapping("/db/profile/manual")
    public Map<String, Object> saveManual(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        String full = dbProfile.saveManual(content);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("manual", dbProfile.manual());
        m.put("profileLength", full.length());
        return m;
    }

    // ---------- 业务口径规则 ----------

    /** 读取口径规则（人工维护，每次问答都会注入提示词） */
    @GetMapping("/rules")
    public Map<String, Object> getRules() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rules", rules.current());
        m.put("configured", rules.configured());
        return m;
    }

    @PostMapping("/rules")
    public Map<String, Object> saveRules(@RequestBody Map<String, String> body) {
        Map<String, Object> ack = new LinkedHashMap<>(rules.save(body.get("rules")));
        ack.put("rules", rules.current());
        return ack;
    }

    // ---------- 分析卡片（沉淀复用） ----------

    @GetMapping("/cards")
    public Map<String, Object> listCards(@RequestParam(value = "sessionId", required = false) String sessionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("cards", cards.list(sessionId));
        return m;
    }

    @GetMapping("/cards/{id}")
    public Map<String, Object> getCard(@PathVariable("id") String id,
                                       @RequestParam(value = "sessionId", required = false) String sessionId) {
        return cards.get(sessionId, id);
    }

    @DeleteMapping("/cards/{id}")
    public Map<String, Object> deleteCard(@PathVariable("id") String id,
                                          @RequestParam(value = "sessionId", required = false) String sessionId) {
        cards.delete(sessionId, id);
        return Map.of("status", "ok", "deleted", id);
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "sessionId", required = false) String sessionId) throws Exception {
        DataTable t = ingestion.fromFile(file);
        // 物化进 H2，**表名按会话隔离**：A 上传不会覆盖 B 的表（核心并发改造点）
        String tableName = "UPLOADED_DATA_" + DataIngestion.safeSid(sessionId);
        ingestion.loadIntoH2(t, tableName);
        // **钉住**为本次分析对象：AI 的探索性查询不能再把它顶掉（否则会出现"传了文件却分析本地库"）
        session.pin(sessionId, t, tableName.toLowerCase());
        agent.invalidate(sessionId);   // 换了数据源：旧问答缓存立即失效
        log.info("上传成功 session={} file={} rows={} cols={} table={}",
                sessionId, file.getOriginalFilename(), t.rowCount(), t.colCount(), tableName.toLowerCase());
        Map<String, Object> m = buildPreview(t);
        m.put("table", tableName.toLowerCase());   // 让前端显示本次上传落到哪张表
        m.put("source", "upload");
        return m;
    }

    @PostMapping("/sql")
    public Map<String, Object> sql(@RequestBody Map<String, String> body) {
        String sql = body.get("sql");
        String sid = body.get("sessionId");
        // 闸门移到 queryToTable 内部做（含跨会话表隔离），这里不再单独调
        DataTable t = ingestion.fromUserSql(sql, sid);
        // 手写 SQL 也是用户显式动作：同样钉住，成为新的分析对象
        session.pin(sid, t, "手写 SQL 的结果集");
        agent.invalidate(sid);         // 手写 SQL 换了一张结果集：旧缓存失效
        Map<String, Object> m = buildPreview(t);
        m.put("source", "sql");
        return m;
    }

    // 只读闸门拦截：返回清晰的 400 + 原因，而不是笼统的 500
    @ExceptionHandler({SecurityException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleGuard(Exception e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "rejected");
        m.put("reason", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(m);
    }

    /**
     * 兜底：任何未预料的异常都返回 **JSON**，不要交给 Spring 默认错误页。
     * 默认错误页按 Accept 协商可能吐 HTML，前端 r.json() 就会炸成
     * "Unexpected token '<'"，把真正的错误原因埋掉。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("reason", e.getClass().getSimpleName() + ": " + e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(m);
    }

    /**
     * 本会话状态：前端刷新/服务重启后用它判断"我这边还有没有分析对象"。
     * 进程重启会清空 H2 与会话表，但浏览器里的 sessionId 还在——
     * 不查这一步，用户会以为自己的文件还在，而 AI 其实已经在答示例表了。
     */
    @GetMapping("/state")
    public Map<String, Object> state(@RequestParam(value = "sessionId", required = false) String sessionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        DataTable t = session.resolve(sessionId);
        boolean pinned = session.hasPinned(sessionId);
        m.put("sessionId", sessionId);
        m.put("pinned", pinned);
        m.put("source", session.sourceName(sessionId));
        m.put("rows", t == null ? 0 : t.rowCount());
        m.put("columns", t == null ? List.of() : t.columns);
        return m;
    }

    /** 多轮对话式分析：带 sessionId，返回结论 + 智能体自选的图表（含数据） */
    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, String> body) {
        String q = body.get("question");
        String sid = body.get("sessionId");
        if (sid == null || sid.isBlank()) sid = "default";
        boolean pinned = session.hasPinned(sid);   // 会顺带尝试从落盘库接回本会话的表
        log.info("问答 session={} 有自己数据={} 来源={} 问题={}", sid, pinned, session.sourceName(sid),
                q == null ? "" : q.substring(0, Math.min(40, q.length())));
        AnalystResult r = agent.analyze(q, sid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("answer", r.answer());
        m.put("chart", r.chart());          // 兼容：第一张图
        m.put("chartData", r.chartData());  // 兼容：第一张图的数据
        m.put("charts", r.charts());        // 一次问答可能给出多张图
        m.put("sessionId", sid);
        m.put("memory", chatSessions.describe(sid));
        m.put("cached", r.cached());        // 命中缓存：同问题+同数据态短时间内复用
        // 本会话到底有没有"用户自己的数据"：没有就说明这次分析的是演示表，
        // 前端据此在答案上方挂一条醒目提示，避免"以为在分析自己文件，其实在分析示例库"。
        m.put("dataReady", session.hasPinned(sid));
        m.put("source", session.sourceName(sid));
        m.put("plan", r.plan());          // 这次的分析计划（自主规划模式）
        m.put("attempts", r.attempts());  // 执行了几轮，>1 说明被自检打回过
        return m;
    }

    /** 清空某会话的多轮记忆（开新会话） */
    @DeleteMapping("/chat")
    public Map<String, Object> clearChat(@RequestBody(required = false) Map<String, String> body) {
        String sid = (body == null) ? null : body.get("sessionId");
        if (sid == null || sid.isBlank()) sid = "default";
        chatSessions.clear(sid);
        return Map.of("status", "ok", "sessionId", sid, "memory", chatSessions.describe(sid));
    }

    private Map<String, Object> buildPreview(DataTable t) {
        int n = Math.min(t.rowCount(), 50);
        List<List<Object>> sample = new ArrayList<>();
        for (int r = 0; r < n; r++) sample.add(t.rows.get(r));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("rows", t.rowCount());
        m.put("columns", t.columns);
        m.put("sample", sample);
        return m;
    }
}
