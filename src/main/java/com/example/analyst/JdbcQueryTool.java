package com.example.analyst;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * AI 查库工具：LLM 生成 SELECT 后由它执行。
 * 关键点：执行前必须先过 SqlGuard 只读闸门——只能查，不能增删改。
 * 查回的结果会写进 AnalysisSession，这样后续统计工具可以接着分析这张表。
 * 数据路由：uploaded_data（用户上传）在本地 H2；配置了外部库时其余查询走外部库。
 */
@Component
public class JdbcQueryTool {

    private final DataIngestion ingestion;
    private final SqlGuard guard;
    private final AnalysisSession session;
    private final ExternalDb externalDb;
    private final ToolBudget budget;

    public JdbcQueryTool(DataIngestion ingestion, SqlGuard guard, AnalysisSession session,
                         ExternalDb externalDb, ToolBudget budget) {
        this.ingestion = ingestion;
        this.guard = guard;
        this.session = session;
        this.externalDb = externalDb;
        this.budget = budget;
    }

    @Tool(description = "用 JDBC 对数据库执行一条只读 SELECT 查询，返回结果摘要（列名、行数、前若干行）。只能查询，不能增删改。" +
            "注意：uploaded_data 是用户上传的本地表；若用户连接了外部数据库，其他表都在外部库。" +
            "这条查询只用于探查，不会替换用户当前的分析对象。")
    public String queryDatabase(@ToolParam(description = "一条 SELECT SQL") String sql, ToolContext ctx) {
        String sid = sid(ctx);
        DataTable t = ingestion.queryToTable(sql, sid);   // 闸门 + uploaded_data 按会话改写都在里面
        session.set(sid, t);                              // 只写临时区，不动用户钉住的分析对象
        StringBuilder sb = new StringBuilder();
        sb.append("查询成功，返回 ").append(t.rowCount()).append(" 行，列：").append(t.columns).append("\n");
        int n = Math.min(t.rowCount(), 10);
        for (int r = 0; r < n; r++) {
            sb.append(t.rows.get(r)).append("\n");
        }
        if (t.rowCount() > n) sb.append("... 其余 ").append(t.rowCount() - n).append(" 行省略");
        if (session.hasPinned(sid)) {
            sb.append("\n[注意] 用户上传/指定的分析对象固定为 ").append(session.sourceName(sid))
              .append("，本次查询不会改变它。后续 describe/correlation 等统计工具仍然作用在")
              .append(session.sourceName(sid)).append(" 上；要分析本次结果，请引导用户先执行该 SQL 使其成为分析对象。");
        }
        return sb.toString();
    }

    @Tool(description = "列出当前可查询的数据表名。用于在查询前了解库里有哪些表。")
    public String listTables(ToolContext ctx) {
        String sid = sid(ctx);
        String head = "";
        // 有明确分析对象时，第一行就锁定它，避免 AI 被示例表带偏
        if (session.hasPinned(sid)) {
            DataTable cur = session.resolve(sid);
            head = "【当前分析对象】" + session.sourceName(sid) + "（" + cur.rowCount() + " 行，列："
                    + cur.columns + "）。除非用户明确要求换表，一切分析都基于这张表。\n其他可用表：";
        }
        String upload = "本会话上传的文件物化在本地表 uploaded_data_" + DataIngestion.safeSid(sid)
                + "（写 SQL 时用 `uploaded_data` 字面即可，会自动改写到这张表）。";
        if (!externalDb.isConfigured()) {
            return head + "本地库有：sales（仅演示用的示例数据，用户未上传时才能用它演示）。" + upload;
        }
        return head + "外部库 " + externalDb.url() + " 共 " + externalDb.tables().size()
                + " 张表：" + externalDb.tables() + "；" + upload;
    }

    /** 从 Spring AI 注入的工具上下文里取会话 ID */
    private String sid(ToolContext ctx) {
        // 查库工具同样计入本次预算
        budget.guard("queryDatabase/listTables");
        Object v = (ctx == null || ctx.getContext() == null) ? null : ctx.getContext().get("sessionId");
        return v == null ? null : String.valueOf(v);
    }
}
