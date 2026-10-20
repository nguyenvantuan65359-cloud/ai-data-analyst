package com.example.analyst;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 只读闸门。
 * 任何要发给数据库的 SQL（无论是用户手写的，还是 AI 生成的）都必须先过这一关。
 * 三道防线：
 *   1) 解析层：必须能被解析成 Select 语句，否则直接拒绝；
 *   2) 关键字层：兜底扫描，防止 SELECT ... INTO OUTFILE 这类“披着查询外衣的写入”；
 *   3) 会话隔离：拒绝跨会话查对方的 uploaded_data 表（多人同时用时**不能**让 A 读 B 的文件）。
 * 注意：这不是靠提示词约束，是代码层硬拦截，绕不过去。
 */
@Component
public class SqlGuard {

    // 真正会改变数据/结构的危险关键字
    private static final List<String> FORBIDDEN = List.of(
            "insert", "update", "delete", "drop", "alter", "truncate",
            "create", "grant", "revoke", "execute", "call", "merge",
            "replace", "into", "lock", "unlock", "comment", "rename", "write"
    );

    // 表名隔离：拦截"UPLOADED_DATA_xxx"中xxx不是自己 sid 的情况
    private static final Pattern OTHER_SESSION_TABLE =
            Pattern.compile("(?i)\\bUPLOADED_DATA_([A-Za-z0-9_]+)\\b");

    public void assertReadOnly(String sql) {
        assertReadOnly(sql, null);
    }

    /**
     * 同上，**外加会话隔离**：禁止跨会话读取别人的 uploaded_data 表。
     * 写字面 "uploaded_data"（无 sid 后缀）会被上游重写器自动改写到自己的表，不受此限；
     * 写 "uploaded_data_<其他 sid>" 会被这里拒绝。
     */
    public void assertReadOnly(String sql, String sessionId) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL 为空");
        }
        String trimmed = sql.trim();

        // 1) 解析层：必须是 SELECT
        try {
            Statement stmt = CCJSqlParserUtil.parse(trimmed);
            if (!(stmt instanceof Select)) {
                throw new SecurityException("只允许 SELECT 查询，禁止 " + stmt.getClass().getSimpleName());
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("SQL 解析失败：" + e.getMessage());
        }

        // 2) 关键字层：兜底扫描（词边界匹配）
        String lower = trimmed.toLowerCase();
        for (String kw : FORBIDDEN) {
            if (lower.matches(".*\\b" + kw + "\\b.*")) {
                throw new SecurityException("检测到禁止关键字：" + kw);
            }
        }

        // 3) 会话隔离：拒绝查询非本会话的 uploaded_data_<sid>
        String mySuffix = DataIngestion.safeSid(sessionId);
        Matcher m = OTHER_SESSION_TABLE.matcher(trimmed);
        while (m.find()) {
            String suffix = m.group(1);
            if (!suffix.equalsIgnoreCase(mySuffix)) {
                throw new SecurityException("只能查询本会话的上传表 uploaded_data_" + mySuffix
                        + "，禁止跨会话查询 uploaded_data_" + suffix);
            }
        }
    }
}
