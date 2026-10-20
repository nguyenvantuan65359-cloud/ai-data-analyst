import java.sql.*;
import java.util.*;

/**
 * 公共只读数据库实测：匿名能否登录、有哪些库、单库多少张表。
 * 只做元数据浏览（DatabaseMetaData）与 SELECT COUNT，绝不写入。
 * 用法：java -cp ".;<mysql-connector-j>" ProbeDb
 */
public class ProbeDb {

    static final class Cand {
        String name, jdbcUrl, user, pass, targetSchema;
        Cand(String name, String jdbcUrl, String user, String pass, String targetSchema) {
            this.name = name; this.jdbcUrl = jdbcUrl; this.user = user;
            this.pass = pass; this.targetSchema = targetSchema;
        }
    }

    public static void main(String[] args) {
        List<Cand> list = List.of(
            new Cand("Ensembl 公共 MySQL",
                     "jdbc:mysql://ensembldb.ensembl.org:3306/", "anonymous", "", null),
            new Cand("UCSC Genome MySQL",
                     "jdbc:mysql://genome-mysql.soe.ucsc.edu:3306/", "genome", "", "hg38"),
            new Cand("EBI Rfam 公共 MySQL",
                     "jdbc:mysql://mysql-rfam-public.ebi.ac.uk:4497/Rfam", "rfam", "", "Rfam")
        );

        for (Cand c : list) {
            System.out.println("=".repeat(74));
            System.out.println("【" + c.name + "】");
            System.out.println("  URL : " + c.jdbcUrl);
            System.out.println("  user: " + (c.user.isEmpty() ? "(空)" : c.user));
            long t0 = System.currentTimeMillis();
            try (Connection conn = DriverManager.getConnection(c.jdbcUrl, c.user, c.pass)) {
                long cost = System.currentTimeMillis() - t0;
                System.out.println("  ✅ 登录成功 (" + cost + "ms)  MySQL 版本 "
                                   + conn.getMetaData().getDatabaseProductVersion());
                DatabaseMetaData md = conn.getMetaData();

                // 1. 列出数据库
                List<String> schemas = new ArrayList<>();
                try (ResultSet rs = md.getCatalogs()) {
                    while (rs.next() && schemas.size() < 200) schemas.add(rs.getString(1));
                }
                System.out.println("  📚 可见库数量: " + schemas.size()
                                   + (schemas.size() == 200 ? "（>=200，已截断）" : ""));
                if (!schemas.isEmpty()) {
                    System.out.println("     前 12 个: " + String.join(", ", schemas.subList(0, Math.min(12, schemas.size()))));
                }

                // 2. 目标库的表清单
                String schema = c.targetSchema;
                if (schema == null && !schemas.isEmpty()) schema = schemas.get(0);
                if (schema != null) {
                    List<String> tables = new ArrayList<>();
                    long t1 = System.currentTimeMillis();
                    try (ResultSet rs = md.getTables(schema, null, "%", new String[]{"TABLE", "VIEW"})) {
                        while (rs.next() && tables.size() < 300) tables.add(rs.getString("TABLE_NAME"));
                    }
                    long cost2 = System.currentTimeMillis() - t1;
                    System.out.println("  📋 库 [" + schema + "] 表数量: " + tables.size()
                                       + (tables.size() == 300 ? "（>=300，已截断）" : "")
                                       + "  内省耗时 " + cost2 + "ms");
                    if (!tables.isEmpty()) {
                        System.out.println("     前 10 张: " + String.join(", ", tables.subList(0, Math.min(10, tables.size()))));
                        // 行数
                        String first = tables.get(0);
                        try (Statement st = conn.createStatement();
                             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + schema + "`.`" + first + "`")) {
                            rs.next();
                            System.out.println("     抽样 " + first + " 行数: " + rs.getLong(1));
                        } catch (Exception e) {
                            System.out.println("     抽样行数失败: " + e.getMessage());
                        }
                    }
                }
                // 3. 写入权限自检（应当被拒绝）
                System.out.print("  🔒 写权限自检: ");
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("DROP TABLE IF EXISTS probe_perm_check");
                    System.out.println("⚠️ 竟然允许写入！不要用它做日常测试");
                } catch (Exception e) {
                    System.out.println("已拒绝写入（只读，安全）");
                }
            } catch (Exception e) {
                System.out.println("  ❌ 失败: " + e.getClass().getSimpleName() + " -> "
                                   + trim(e.getMessage()));
            }
        }
    }

    static String trim(String s) {
        if (s == null) return "";
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
