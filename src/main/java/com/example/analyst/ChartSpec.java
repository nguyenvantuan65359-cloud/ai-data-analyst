package com.example.analyst;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图表规格：智能体根据问题自行选择的图型（数据模型）与取数 SQL。
 * 约定：sql 必须返回 1~2 列——第 1 列为 X（类目或数值），第 2 列为 Y（数值）。
 * 直方图特殊：sql 只返回 1 列原始数值，由前端分箱。
 */
public class ChartSpec {

    public String type;    // hist / bar / line / scatter / pie
    public String title;
    public String sql;
    public String xName;
    public String yName;

    private static final java.util.Set<String> VALID =
            java.util.Set.of("hist", "bar", "line", "scatter", "pie", "none");

    public static ChartSpec from(Map<String, Object> m) {
        ChartSpec s = new ChartSpec();
        s.type = str(m.get("type"));
        if (s.type != null) s.type = s.type.toLowerCase().trim();
        if (s.type == null || !VALID.contains(s.type)) s.type = "bar";
        s.title = str(m.get("title"));
        s.sql = str(m.get("sql"));
        // LLM 偶尔会带上尾随分号或反引号，统一清掉，免得闸门/驱动报错
        if (s.sql != null) s.sql = s.sql.trim().replace("`", "");
        s.xName = str(m.get("x"));
        s.yName = str(m.get("y"));
        return s;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("sql", sql);
        m.put("x", xName);
        m.put("y", yName);
        return m;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
