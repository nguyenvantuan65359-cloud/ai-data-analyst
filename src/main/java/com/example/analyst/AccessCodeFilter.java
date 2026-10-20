package com.example.analyst;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 最小访问控制：这个系统要给别人用、甚至放到公网，就必须有一道门。
 *
 * 设计取舍（刻意做薄）：
 * - 不做用户体系、不做登录页，靠一个共享访问口令，链接里带上 ?code= 即可进入；
 * - 页面本身放行，口令校验只针对 /api/**，前端拿到口令后自动带在请求头里；
 * - access-code 留空 = 不启用（本机自用时的默认状态，零负担）。
 *
 * 注意：这不是安全方案，是"别把裸数据库接口挂在公网上"的最低门槛。
 * 真要做权限分级（谁能连哪个库）请另做用户体系。
 */
@Component
@Order(1)
public class AccessCodeFilter implements Filter {

    @Value("${app.analyst.access-code:}")
    private String accessCode;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (accessCode == null || accessCode.isBlank()) { chain.doFilter(req, res); return; }

        HttpServletRequest h = (HttpServletRequest) req;
        String path = h.getRequestURI();
        // 静态页面放行：否则连输入口令的页面都打不开
        if (path.equals("/") || path.endsWith(".html") || path.endsWith(".js")
                || path.endsWith(".css") || path.startsWith("/favicon")) {
            chain.doFilter(req, res);
            return;
        }

        String given = h.getHeader("X-Access-Code");
        if (isBlank(given)) given = h.getParameter("code");
        if (isBlank(given) && h.getCookies() != null) {
            for (Cookie c : h.getCookies()) if ("analyst_code".equals(c.getName())) given = c.getValue();
        }

        if (accessCode.equals(given)) { chain.doFilter(req, res); return; }

        HttpServletResponse r = (HttpServletResponse) res;
        r.setStatus(401);
        r.setContentType("application/json;charset=UTF-8");
        r.getWriter().write("{\"status\":\"unauthorized\",\"reason\":\"访问口令不正确，请向管理员索取带 code 的链接\"}");
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
