package com.example.analyst;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 本次问答的"数字出处"记录：工具返回了什么、图表查回了什么、数据画像里有哪些数。
 *
 * 为什么要有它：此前的算术校验只能判断"数字能否由文中其他数字复现"——
 * 模型完全可以编一个自洽但错的算式蒙混过去。要真正防住，得回答一个问题：
 * **这个数字到底是不是从工具/数据里来的？**
 *
 * 记法与 ThreadLocal：一次请求一个线程，多会话并发不能互相污染。
 */
@Component
public class ToolTrace {

    private static final ThreadLocal<StringBuilder> TL = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> TOOLS = new ThreadLocal<>();

    public void begin() {
        TL.set(new StringBuilder());
        TOOLS.set(new ArrayList<>());
    }

    public void end() {
        TL.remove();
        TOOLS.remove();
    }

    /** 记录一次工具调用的返回（由包装器自动调用） */
    public void record(String toolName, String output) {
        StringBuilder sb = TL.get();
        if (sb == null) return;
        List<String> names = TOOLS.get();
        if (names != null && !names.contains(toolName)) names.add(toolName);
        sb.append("\n[").append(toolName).append("] ").append(output == null ? "" : output).append('\n');
    }

    /** 记录图表取数结果（不走工具，直接查库，所以单独记一笔） */
    public void recordChart(String title, List<List<Object>> rows) {
        StringBuilder sb = TL.get();
        if (sb == null || rows == null) return;
        sb.append("\n[图表数据] ").append(title == null ? "" : title).append(' ');
        for (List<Object> r : rows) {
            sb.append(r).append(' ');
            if (sb.length() > 200_000) break;      // 兜底，别把内存撑爆
        }
        sb.append('\n');
    }

    /** 记录数据画像（提示词里那一段），它的数字也是合法出处 */
    public void recordProfile(String profile) {
        StringBuilder sb = TL.get();
        if (sb == null || profile == null) return;
        sb.append("\n[数据画像] ").append(profile).append('\n');
    }

    public String text() {
        StringBuilder sb = TL.get();
        return sb == null ? "" : sb.toString();
    }

    public List<String> toolsUsed() {
        List<String> n = TOOLS.get();
        return n == null ? List.of() : List.copyOf(n);
    }
}
