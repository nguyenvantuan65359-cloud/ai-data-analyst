package com.example.analyst;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;

/**
 * 数据接入层：把“文件 / 用户手写 SQL”收敛成统一的 DataTable。
 * 所有进库的 SQL 都先过 SqlGuard 只读闸门。
 */
@Service
public class DataIngestion {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DataIngestion.class);

    private final JdbcTemplate jdbcTemplate;   // 本地 H2
    private final ExternalDb externalDb;
    private final SqlGuard sqlGuard;
    /** 单次查询最多取回多少行：防止一条 SELECT * 把整张千万行大表拖进内存 */
    @Value("${app.analyst.max-query-rows:200000}")
    private int maxQueryRows;

    public DataIngestion(JdbcTemplate jdbcTemplate, ExternalDb externalDb, SqlGuard sqlGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.externalDb = externalDb;
        this.sqlGuard = sqlGuard;
    }

    /** 文件导入：支持 CSV / Excel(.xlsx/.xls) */
    public DataTable fromFile(MultipartFile file) throws Exception {
        String name = file.getOriginalFilename();
        if (name == null) throw new IllegalArgumentException("文件名缺失");
        String lower = name.toLowerCase();
        if (lower.endsWith(".csv")) {
            return fromCsv(file.getInputStream());
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".xlsm")) {
            // .xlsm 是带宏的 OOXML，POI 按普通工作簿读取（仅取数据，不执行宏）
            return fromExcel(file.getInputStream());
        }
        throw new IllegalArgumentException("仅支持 .csv / .xlsx / .xls / .xlsm 文件");
    }

    public DataTable fromCsv(InputStream in) throws Exception {
        // 剥掉 UTF-8 BOM（Excel 另存的 CSV 常带），否则它会混进第一个列名
        PushbackInputStream pin = new PushbackInputStream(in, 3);
        byte[] bom = new byte[3];
        int n = pin.read(bom);
        if (!(n == 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF)) {
            if (n > 0) pin.unread(bom, 0, n);
        }
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(pin, StandardCharsets.UTF_8,
                CSVFormat.EXCEL.withFirstRecordAsHeader())) {
            columns.addAll(parser.getHeaderNames());
            for (var rec : parser) {
                List<Object> row = new ArrayList<>();
                for (String h : columns) row.add(rec.get(h));
                rows.add(row);
            }
        }
        return new DataTable(columns, rows);
    }

    public DataTable fromExcel(InputStream in) throws Exception {
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            Iterator<Row> it = sheet.iterator();
            if (it.hasNext()) {
                Row header = it.next();
                for (Cell c : header) columns.add(cellText(c));
            }
            while (it.hasNext()) {
                Row r = it.next();
                List<Object> row = new ArrayList<>();
                for (int i = 0; i < columns.size(); i++) {
                    Cell c = r.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    row.add(readCell(c));
                }
                rows.add(row);
            }
        }
        return new DataTable(columns, rows);
    }

    /** 表头统一转成字符串：公式/数字/布尔/错误单元格都安全处理，不会抛异常 */
    private String cellText(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> String.valueOf(c.getNumericCellValue());
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            case FORMULA -> switch (c.getCachedFormulaResultType()) {
                case STRING -> c.getStringCellValue();
                case NUMERIC -> String.valueOf(c.getNumericCellValue());
                case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
                default -> "";
            };
            default -> "";
        };
    }

    /** 数据单元格：数字/布尔/字符串/公式缓存结果都支持；错误公式与空单元格当 null */
    private Object readCell(Cell c) {
        if (c == null) return null;
        return switch (c.getCellType()) {
            case NUMERIC -> c.getNumericCellValue();
            case BOOLEAN -> c.getBooleanCellValue();
            case STRING -> c.getStringCellValue();
            case FORMULA -> switch (c.getCachedFormulaResultType()) {
                case NUMERIC -> c.getNumericCellValue();
                case BOOLEAN -> c.getBooleanCellValue();
                case STRING -> c.getStringCellValue();
                default -> null; // 公式结果为 ERROR / BLANK 时当空
            };
            default -> null; // BLANK / ERROR 当空
        };
    }

    /** 用户手写 SQL 查询结果 → DataTable（先过只读闸门） */
    public DataTable fromUserSql(String sql) {
        return fromUserSql(sql, null);
    }

    /** 同上，**按会话隔离**：SQL 里 uploaded_data 字面会被重写成本会话表；闸门移到 queryToTable 统一做 */
    public DataTable fromUserSql(String sql, String sessionId) {
        return queryToTable(sql, sessionId);
    }

    /** 内部查询公共方法：AI 查库工具调用时也应先过闸门再进来（不带 sessionId 默认走 "default"） */
    public DataTable queryToTable(String sql) { return queryToTable(sql, null); }

    /**
     * 带会话隔离的查询：闸门（含表隔离）→ 改写 uploaded_data 字面 → 路由到本地/外部库 → 执行。
     * 任何来源的 SQL（用户手写 / AI 图表指令）都必须走这里。
     */
    public DataTable queryToTable(String sql, String sessionId) {
        sqlGuard.assertReadOnly(sql, sessionId);          // 统一闸门（含跨会话表拦截）
        String rewritten = rewriteUploadedData(sql, sessionId);

        // 行数上限：用 setMaxRows 让 JDBC 侧就截断，超限直接拒绝而不是把整张大表拉进 JVM
        JdbcTemplate jt = pickTemplate(rewritten);
        int cap = maxQueryRows > 0 ? maxQueryRows : 200000;
        List<Map<String, Object>> list = jt.query((Connection con) -> {
            PreparedStatement ps = con.prepareStatement(rewritten);
            ps.setMaxRows(cap + 1);
            return ps;
        }, new ColumnMapRowMapper());
        if (list.size() > cap) {
            throw new IllegalArgumentException("结果超过 " + cap + " 行（未 preview 到全部），请先用 SQL 做聚合/过滤再分析；"
                    + "例如 `SELECT 分组列, SUM(数值列) ... GROUP BY 分组列`。可用 app.analyst.max-query-rows 调整上限。");
        }
        if (list.isEmpty()) return new DataTable(new ArrayList<>(), new ArrayList<>());
        List<String> columns = new ArrayList<>(list.get(0).keySet());
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> m : list) {
            List<Object> row = new ArrayList<>();
            for (String c : columns) row.add(m.get(c));
            rows.add(row);
        }
        return new DataTable(columns, rows);
    }

    /** 把 SQL 里字面 "uploaded_data" 改成本会话的表名（已经带 sid 后缀的不再加） */
    static String rewriteUploadedData(String sql, String sessionId) {
        if (sql == null) return null;
        String withSid = "UPLOADED_DATA_" + safeSid(sessionId);
        // (?i) 忽略大小写；\b 词边界；(?!_) 防止把 UPLOADED_DATA_xxx 重复加 sid
        return sql.replaceAll("(?i)\\bUPLOADED_DATA\\b(?!_)", withSid);
    }

    /** 把会话 ID 变成可安全拼到表名/标识符里的串：白名单字符 + 长度截断 */
    public static String safeSid(String sid) {
        if (sid == null) return "default";
        String s = sid.replaceAll("[^A-Za-z0-9_]", "");
        if (s.isEmpty()) s = "default";
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    /**
     * 服务重启后把本会话的上传表从落盘库找回来（表名可由 sid 反推，不需要额外记账）。
     * 找不到返回 null —— 调用方据此判断"这个会话确实没有数据"。
     */
    public DataTable restoreSessionTable(String sessionId) {
        String table = "UPLOADED_DATA_" + safeSid(sessionId);
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = UPPER(?)",
                    Integer.class, table);
            if (n == null || n == 0) { log.info("会话 {} 无可恢复表（{}）", sessionId, table); return null; }
            DataTable t = queryToTable("SELECT * FROM " + table, sessionId);
            log.info("会话 {} 从落盘库恢复表 {}（{} 行）", sessionId, table, t.rowCount());
            return t;
        } catch (Exception e) {
            log.warn("会话 {} 恢复表 {} 失败：{}", sessionId, table, e.toString());
            return null;
        }
    }

    /** 查询路由：uploaded_data（用户上传）永远在本地 H2；配置了外部库时，其余查询走外部库 */
    private JdbcTemplate pickTemplate(String sql) {
        if (externalDb.isConfigured() && !sql.toLowerCase().contains("uploaded_data")) {
            return externalDb.template();
        }
        return jdbcTemplate;
    }

    /** H2 保留字：列名撞上就加前缀下划线（month→_MONTH），避免建表/查询语法错 */
    private static final java.util.Set<String> RESERVED = java.util.Set.of(
            "MONTH", "RETURNS", "ORDER", "GROUP", "USER", "YEAR", "DATE", "TIME",
            "VALUE", "KEY", "LEVEL", "DESC", "ASC", "COMMENT", "CHECK", "DEFAULT",
            "PRIMARY", "FOREIGN", "INDEX", "COLUMN", "CREATE", "DROP", "ALTER",
            "TABLE", "SELECT", "INSERT", "UPDATE", "DELETE", "INTO", "FROM", "WHERE",
            "AND", "OR", "NOT", "NULL", "TRUE", "FALSE", "LIKE", "IN", "IS", "BETWEEN",
            "DISTINCT", "LIMIT", "OFFSET", "JOIN", "ON", "AS", "BY", "HAVING", "CASE",
            "WHEN", "THEN", "ELSE", "END", "FOR", "WITH", "ALL", "ANY", "INTERVAL");

    /**
     * 列名内核：保留字母（**含中文**）数字下划线，其余转下划线；转大写；
     * 首字符是数字或撞上保留字时加前缀下划线。转大写是为了让 AI 写的
     * `SELECT region` 也能命中（H2 对未加引号的标识符同样折叠成大写）。
     */
    private static String identCore(String raw) {
        String s = raw == null ? "" : raw.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            sb.append(Character.isLetterOrDigit(ch) || ch == '_' ? ch : '_');
        }
        String out = sb.toString();
        if (out.isEmpty() || Character.isDigit(out.charAt(0))) out = "_" + out;
        out = out.toUpperCase(java.util.Locale.ROOT);
        if (RESERVED.contains(out)) out = "_" + out;
        return out;
    }

    /** 把内存 DataTable 物化进 H2，成为可被 SQL / AI 查库工具直接查询的真实表。
     *  列类型按抽样推断：数值列→DOUBLE，其余→VARCHAR；列名做安全化（去非法字符、避开保留字）。 */
    public void loadIntoH2(DataTable t, String tableName) {
        if (t.columns.isEmpty()) return;
        // 列名先算"内核"，去重后再整体加双引号。
        // 以前把所有非 ASCII 字符替换成下划线，于是「地区」这种中文表头在 H2 里变成 "__"，
        // AI 写 SELECT 地区 FROM uploaded_data 必然报"列不存在"——真实业务表几乎都带中文表头，必须修。
        List<String> cores = new ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (String c : t.columns) {
            String base = identCore(c);
            String s = base;
            int k = 2;
            while (!used.add(s)) s = base + "_" + (k++);
            cores.add(s);
        }
        List<String> safe = new ArrayList<>();
        for (String c : cores) safe.add("\"" + c + "\"");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName);
        boolean[] numeric = new boolean[safe.size()];
        List<String> colDefs = new ArrayList<>();
        int probe = Math.min(t.rowCount(), 100);
        for (int i = 0; i < safe.size(); i++) {
            boolean isNum = true;
            for (int r = 0; r < probe; r++) {
                Object v = t.rows.get(r).get(i);
                if (v == null) continue;
                if (v instanceof Number) continue;
                if (v instanceof String s && !s.isBlank()) {
                    try { Double.parseDouble(s.trim()); }
                    catch (Exception e) { isNum = false; break; }
                } else { isNum = false; break; }
            }
            numeric[i] = isNum;
            colDefs.add(safe.get(i) + (isNum ? " DOUBLE" : " VARCHAR(4000)"));
        }
        jdbcTemplate.execute("CREATE TABLE " + tableName + " (" + String.join(", ", colDefs) + ")");

        String placeholders = String.join(", ", java.util.Collections.nCopies(safe.size(), "?"));
        String insert = "INSERT INTO " + tableName + " (" + String.join(", ", safe) + ") VALUES (" + placeholders + ")";
        for (List<Object> row : t.rows) {
            Object[] params = new Object[safe.size()];
            for (int i = 0; i < safe.size(); i++) {
                Object v = row.get(i);
                if (numeric[i] && v instanceof String s && !s.isBlank()) {
                    try { params[i] = Double.parseDouble(s.trim()); }
                    catch (Exception e) { params[i] = v; }
                } else if (v == null || (v instanceof String s && s.isBlank())) {
                    params[i] = null;
                } else {
                    params[i] = v;
                }
            }
            jdbcTemplate.update(insert, params);
        }
    }
}
