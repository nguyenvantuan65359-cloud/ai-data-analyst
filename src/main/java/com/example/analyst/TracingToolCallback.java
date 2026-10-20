package com.example.analyst;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 工具回调包装器：把每次工具返回原样记进 ToolTrace，业务逻辑一行不动。
 *
 * 为什么用包装而不是在每个 @Tool 方法里手写 trace.record(...)：
 * 12 个工具、每个都有多个 return 分支（参数错、样本不足、正常返回），
 * 手写迟早漏一个；包在回调层是"必经之路"，漏不掉。
 */
public class TracingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolTrace trace;

    public TracingToolCallback(ToolCallback delegate, ToolTrace trace) {
        this.delegate = delegate;
        this.trace = trace;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        try {
            String out = delegate.call(toolInput);
            trace.record(name(), out);
            return out;
        } catch (RuntimeException e) {
            trace.record(name(), "执行失败：" + e.getMessage());   // 失败也算一次"工具输出"
            throw e;
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            String out = delegate.call(toolInput, toolContext);
            trace.record(name(), out);
            return out;
        } catch (RuntimeException e) {
            trace.record(name(), "执行失败：" + e.getMessage());
            throw e;
        }
    }

    private String name() {
        try {
            return delegate.getToolDefinition().name();
        } catch (Exception e) {
            return "tool";
        }
    }
}
