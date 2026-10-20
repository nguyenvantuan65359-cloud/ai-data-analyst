package com.example.analyst;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据字典（记忆文件）：连接数据库后，自动内省所有表的结构、行数、样例数据，
 * 再由 LLM 解读“每张表存的是什么、可以用来做什么分析”，结果落盘为 markdown 记忆文件。
 *
 * 作用：让后续的每一次提问都不再是“蒙眼开枪”——智能体先读这本字典，
 * 才知道该翻哪张表、该用哪种统计方法。
 */
@Component
public class DbProfile {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DbProfile.class);
    /** 单次 LLM 调用覆盖的表数量；拆批避免长响应读超时 */
    private static final int PROFILE_BATCH = 6;

    private final ExternalDb externalDb;
    private final JdbcTemplate localJdbc;
    private final ChatClient chatClient;
    private final Path file;
    private final long maxChars;
    private final int maxTables;

    private static final String MANUAL_BEGIN = "<!-- MANUAL BEGIN -->";
    private static final String MANUAL_END = "<!-- MANUAL END -->";

    private volatile String markdown = "";

    /** 人工修订区内容（字典重建时要原样保留，不被覆盖） */
    private volatile String manual = "";

    /**
     * 这本字典描述的是哪个数据源："" = 本地 H2，非空 = 外部库 URL。
     *
     * 为什么必须记住它：字典是落盘持久化的，而外部库连接只存在内存里（重启即失效）。
     * 早期版本重启后无条件把磁盘上的字典读回来注入提示词，于是就出现
     * "用户上传了文件，AI 却在翻上次连的 Rfam 外部库"——字典和实际能查的库对不上。
     * 现在只有"字典描述的数据源 == 当前实际数据源"时才允许注入。
     */
    private volatile String scopeUrl = "";

    public DbProfile(ExternalDb externalDb, JdbcTemplate jdbcTemplate, ChatClient.Builder builder,
                     @Value("${app.analyst.profile-file:db_profile.md}") String file,
                     @Value("${app.analyst.profile-max-chars:12000}") long maxChars,
                     @Value("${app.analyst.profile-max-tables:20}") int maxTables) {
        this.externalDb = externalDb;
        this.localJdbc = jdbcTemplate;
        this.chatClient = builder.build();
        this.file = Paths.get(file);
        this.maxChars = maxChars;
        this.maxTables = maxTables;
        try {
            if (Files.exists(this.file)) {
                String disk = Files.readString(this.file, StandardCharsets.UTF_8);
                // 只恢复"与当前数据源一致"的字典；对不上就当作没有字典（文件仍留在磁盘上备查）
                if (sameSource(parseScope(disk))) {
                    this.markdown = disk;
                    this.scopeUrl = parseScope(disk);
                } else {
                    log.info("磁盘上的数据字典属于 {}，与当前数据源不一致，本次启动不加载", parseScope(disk));
                }
                this.manual = extractManual(disk);
            }
        } catch (Exception ignored) {
            // 记忆文件读不到不阻塞启动
        }
    }

    /** 当前记忆文件内容（未生成时为空串） */
    public String current() { return markdown; }

    /**
     * 可以注入提示词的字典：只有描述的就是当前数据源时才算数。
     * 外部库断开/换库之后，旧字典一律不再注入，避免 AI 拿旧字典去猜表名。
     */
    public String active() {
        return sameSource(scopeUrl) ? markdown : "";
    }

    /** 字典描述的数据源是不是当前这个 */
    private boolean sameSource(String dictScope) {
        String cur = externalDb.isConfigured() ? externalDb.url() : "";
        String d = dictScope == null ? "" : dictScope.trim();
        return d.equalsIgnoreCase(cur == null ? "" : cur.trim());
    }

    /** 从字典头部的「- 数据源：外部库 <url>」还原数据源；本地 H2 返回空串 */
    private static String parseScope(String md) {
        if (md == null) return "";
        for (String line : md.split("\\R")) {
            String l = line.trim();
            if (l.startsWith("- 数据源：")) {
                String v = l.substring("- 数据源：".length()).trim();
                int idx = v.indexOf("外部库 ");
                return idx >= 0 ? v.substring(idx + "外部库 ".length()).trim() : "";
            }
        }
        return "";
    }

    /** 内省库结构 → AI 解读 → 写记忆文件，返回字典全文 */
    public String build() {
        JdbcTemplate jt = externalDb.isConfigured() ? externalDb.template() : localJdbc;
        String scope = externalDb.isConfigured() ? ("外部库 " + externalDb.url()) : "本地 H2（示例库）";
        this.scopeUrl = externalDb.isConfigured() ? externalDb.url() : "";

        StringBuilder raw = new StringBuilder();
        List<String> all = new ArrayList<>();
        try {
            all.addAll(tableNames(jt));
        } catch (Exception e) {
            raw.append("读取表清单失败：").append(e.getMessage()).append("\n");
        }

        // 大库保护：只为前 maxTables 张表建字典，避免 COUNT(*) 全量扫表把请求拖死
        List<String> tables = all.size() > maxTables ? all.subList(0, maxTables) : all;
        if (all.size() > maxTables) {
            raw.append("# 说明：该数据源共 ").append(all.size())
               .append(" 张表，本次仅为前 ").append(maxTables)
               .append(" 张建字典（可用 app.analyst.profile-max-tables 调整）。\n");
        }

        // 逐表收集原始结构，留待分批送入 LLM
        List<String> segments = new ArrayList<>();
        for (String t : tables) {
            StringBuilder seg = new StringBuilder("\n### 表 ").append(t).append("\n");
            seg.append("- 行数：").append(count(jt, t)).append("\n");
            try {
                seg.append("- 字段：").append(columns(jt, t)).append("\n");
                seg.append("- 样例：").append(samples(jt, t)).append("\n");
            } catch (Exception e) {
                seg.append("- 结构读取失败：").append(e.getMessage()).append("\n");
            }
            segments.add(seg.toString());
        }
        String headRaw = raw.toString();

        // 分批调用 LLM：一次让模型吐出几十张表的完整字典，响应过长会读超时
        // （连接真实大库实测会报 "Error reading response"）。每批 PROFILE_BATCH 张，
        // 各自独立调用，某一批失败只降级该批，不影响整体。
        StringBuilder body = new StringBuilder(headRaw.replaceFirst("^# 说明：", "\n> 说明："));
        for (int i = 0; i < segments.size(); i += PROFILE_BATCH) {
            int end = Math.min(i + PROFILE_BATCH, segments.size());
            String chunk = String.join("", segments.subList(i, end));
            try {
                body.append(aiSummary(scope, chunk)).append("\n");
            } catch (Exception e) {
                log.warn("数据字典第 {}-{} 张表 AI 解读失败，降级输出原始结构：{}", i + 1, end, e.getMessage());
                body.append(chunk);
            }
        }

        // 人工修订区：LLM 猜表含义一定会有猜错的时候，允许人工改写并长期保留
        String manualBlock = manual.isBlank() ? "" :
                "\n\n---\n\n## 人工修订（不会被自动重建覆盖）\n" + MANUAL_BEGIN + "\n" + manual.strip() + "\n" + MANUAL_END + "\n";

        markdown = "# 数据字典（记忆文件）\n\n- 数据源：" + scope
                + "\n- 生成时间：" + java.time.LocalDateTime.now()
                + (manualBlock.isEmpty() ? "" : "\n- 含人工修订：是")
                + "\n\n" + body + manualBlock;
        try {
            Files.writeString(this.file, markdown, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 写盘失败不影响内存中的字典
        }
        return markdown;
    }

    /** 保存人工修订：整段替换 <!-- MANUAL ... --> 之间的内容，并立即合并进当前字典 */
    public String saveManual(String content) {
        String body = content == null ? "" : content.strip();
        this.manual = body;
        String base = markdown;
        int b = base.indexOf(MANUAL_BEGIN), e = base.indexOf(MANUAL_END);
        if (b >= 0 && e > b) {
            base = base.substring(0, b + MANUAL_BEGIN.length()) + "\n" + body + "\n" + base.substring(e);
        } else if (!body.isBlank()) {
            base = base + "\n\n## 人工修订（不会被自动重建覆盖）\n" + MANUAL_BEGIN + "\n" + body + "\n" + MANUAL_END + "\n";
        }
        this.markdown = base;
        try {
            Files.writeString(this.file, base, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 写盘失败不影响内存中的字典
        }
        return base;
    }

    public String manual() { return manual; }

    private static String extractManual(String text) {
        if (text == null) return "";
        int b = text.indexOf(MANUAL_BEGIN), e = text.indexOf(MANUAL_END);
        if (b < 0 || e <= b) return "";
        return text.substring(b + MANUAL_BEGIN.length(), e).strip();
    }

    /** 断开外部库时清空记忆文件（内存层清掉，磁盘文件保留人工修订区供下次复用） */
    public void clear() { markdown = ""; scopeUrl = ""; }

    /** 截取一段给系统提示词用（避免超长） */
    public String brief() {
        if (markdown.length() <= maxChars) return markdown;
        return markdown.substring(0, (int) maxChars) + "\n...（字典过长，已截断）";
    }

    // ---------- 内省 ----------

    private List<String> tableNames(JdbcTemplate jt) throws Exception {
        List<String> ts = new ArrayList<>();
        try (Connection c = jt.getDataSource().getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next() && ts.size() < 100) {
                    String schem = rs.getString("TABLE_SCHEM");
                    if (schem != null && schem.equalsIgnoreCase("INFORMATION_SCHEMA")) continue; // 跳过系统表
                    ts.add(rs.getString("TABLE_NAME"));
                }
            }
        }
        return ts;
    }

    private String count(JdbcTemplate jt, String table) {
        try {
            Long n = jt.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            return String.valueOf(n);
        } catch (Exception e) {
            return "未知";
        }
    }

    private String columns(JdbcTemplate jt, String table) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Connection c = jt.getDataSource().getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getColumns(null, null, table, "%")) {
                while (rs.next()) {
                    if (sb.length() > 0) sb.append("，");
                    sb.append(rs.getString("COLUMN_NAME")).append("(").append(rs.getString("TYPE_NAME")).append(")");
                }
            }
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }

    private String samples(JdbcTemplate jt, String table) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Connection c = jt.getDataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + table + " LIMIT 3")) {
            int n = rs.getMetaData().getColumnCount();
            int k = 0;
            while (rs.next() && k < 3) {
                k++;
                sb.append("[");
                for (int i = 1; i <= n; i++) {
                    if (i > 1) sb.append("，");
                    String label = rs.getMetaData().getColumnLabel(i);
                    // 二进制/大对象列不取值：BLOB 的 toString 是无意义的 "[B@xxxx"，
                    // 既看不出业务含义，还会把给 LLM 的提示词撑爆（连真实大库必踩）
                    if (isLargeType(rs.getMetaData().getColumnType(i))) {
                        sb.append(label).append("=(二进制/大字段，略)");
                        continue;
                    }
                    sb.append(label).append("=").append(clip(rs.getObject(i)));
                }
                sb.append("] ");
            }
        }
        return sb.length() == 0 ? "（无样例）" : sb.toString();
    }

    /** 单个值截断，防止超长文本挤占字典篇幅 */
    private String clip(Object obj) {
        if (obj == null) return "null";
        String out = String.valueOf(obj).replaceAll("\\s+", " ").trim();
        return out.length() <= 40 ? out : out.substring(0, 40) + "…";
    }

    /** 二进制 / 大对象 / 长文本类型判定 */
    private boolean isLargeType(int sqlType) {
        return switch (sqlType) {
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY,
                 Types.BLOB, Types.CLOB, Types.NCLOB, Types.LONGVARCHAR,
                 Types.LONGNVARCHAR, Types.SQLXML, Types.OTHER, Types.NULL -> true;
            default -> false;
        };
    }

    // ---------- AI 语义解读 ----------

    private String aiSummary(String scope, String raw) {
        String prompt = """
                你是数据治理与 BI 专家。下面是从某个数据库自动内省出来的原始结构信息（表名、字段类型、行数、样例数据）。

                数据源：{{SCOPE}}

                请输出一份**中文数据字典**，要求：
                1. 对每张表写清楚：这张表存放的是什么信息（一句话概括）；
                2. 列出关键字段及其业务含义；
                3. 说明这张表可以用来做什么分析（2-3 条，要具体，比如“按 region 分组看各区销售额排名”）；
                4. 标明你觉得值得注意的数据质量问题（如缺失率高、疑似枚举值、时间字段等）。
                5. 只能依据下面给出的真实结构信息解读，**严禁编造样本中看不到的业务含义**；信息不足就写“信息不足，无法判断”。

                输出格式：markdown，每张表一个三级小节，不要输出多余的开头客套话。

                原始结构信息：
                {{RAW}}
                """.replace("{{SCOPE}}", scope).replace("{{RAW}}", raw);

        String out = chatClient.prompt().user(prompt).call().content();
        return (out == null || out.isBlank()) ? raw : out;
    }
}
