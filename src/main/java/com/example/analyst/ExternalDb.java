package com.example.analyst;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

/**
 * 外部数据源（用户在前端配置，可选）。
 * 连接成功后保存 JdbcTemplate 与表清单；未配置时所有查询走本地 H2。
 * 只保存连接信息，不缓存查询结果；所有经此执行的 SQL 仍须先过 SqlGuard 只读闸门。
 */
@Component
public class ExternalDb {

    private volatile JdbcTemplate template;   // null = 未配置
    private volatile String url = "";
    private volatile List<String> tables = List.of();

    public boolean isConfigured() { return template != null; }
    public JdbcTemplate template() { return template; }
    public String url() { return url; }
    public List<String> tables() { return tables; }

    public void clear() {
        template = null;
        url = "";
        tables = List.of();
    }

    /** 测试连接；成功则保存配置并返回该库的表清单（最多 100 张） */
    @SuppressWarnings("unchecked")
    public List<String> connect(String jdbcUrl, String username, String password) throws Exception {
        String driver = detectDriver(jdbcUrl);
        Class<? extends java.sql.Driver> cls = (Class<? extends java.sql.Driver>) Class.forName(driver);
        List<String> ts = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl, username, password)) {
            DatabaseMetaData md = c.getMetaData();
            try (var rs = md.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next() && ts.size() < 100) {
                    String schem = rs.getString("TABLE_SCHEM");
                    if (schem != null && schem.equalsIgnoreCase("INFORMATION_SCHEMA")) continue; // 跳过 H2 系统表
                    ts.add(rs.getString("TABLE_NAME"));
                }
            }
        }
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(cls);
        ds.setUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        this.template = new JdbcTemplate(ds);
        this.url = jdbcUrl;
        this.tables = List.copyOf(ts);
        return ts;
    }

    private String detectDriver(String url) {
        String u = url == null ? "" : url.trim().toLowerCase();
        if (u.startsWith("jdbc:mysql")) return "com.mysql.cj.jdbc.Driver";
        if (u.startsWith("jdbc:postgresql")) return "org.postgresql.Driver";
        if (u.startsWith("jdbc:sqlserver")) return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        if (u.startsWith("jdbc:h2")) return "org.h2.Driver";
        throw new IllegalArgumentException("暂不支持该数据库类型（当前支持 MySQL / PostgreSQL / SQL Server / H2）");
    }
}
