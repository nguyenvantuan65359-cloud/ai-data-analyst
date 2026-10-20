package com.example.analyst;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 单次问答的工具调用预算。
 *
 * 为什么需要：配置项 `app.analyst.max-agent-steps` 一直在，但 Spring AI 的工具循环是框架内部实现的，
 * 从来没有被真正接上过——也就是说模型想调 20 次工具，没人拦。这个类把上限做在**工具入口**上：
 * 每次调用先记一笔，超了就抛异常，异常信息会作为工具结果回给模型，明令它停止取数、直接作答。
 * 再加上编排层的"预算耗尽后强制无工具收尾"，终止是有保证的，不依赖模型自觉。
 *
 * 用 ThreadLocal 而不是实例字段：一次请求一个线程，多会话并发之间不能互相扣额度。
 */
@Component
public class ToolBudget {

    @Value("${app.analyst.max-tool-calls:50}")
    private int configuredMax;

    private static final ThreadLocal<State> TL = new ThreadLocal<>();

    private static final class State {
        final int max;
        int used;
        boolean exhausted;
        State(int max) { this.max = max; }
    }

    /** 开始一次问答：重置计数。max<=0 时用配置值 */
    public void begin(int max) {
        TL.set(new State(max > 0 ? max : configuredMax));
    }

    public void end() { TL.remove(); }

    public boolean exhausted() {
        State s = TL.get();
        return s != null && s.exhausted;
    }

    public int used() {
        State s = TL.get();
        return s == null ? 0 : s.used;
    }

    public int max() {
        State s = TL.get();
        return s == null ? configuredMax : s.max;
    }

    /**
     * 每个 @Tool 方法入口调用一次。
     * 超预算抛异常：Spring AI 会把 message 当作工具执行失败回给模型，
     * 模型看到"不要再调工具"就会收尾（编排层还有兜底，见 AnalystAgent）。
     */
    void guard(String tool) {
        State s = TL.get();
        if (s == null) return;            // 没走编排（比如单测直接调），不限制
        if (++s.used > s.max) {
            s.exhausted = true;
            throw new IllegalStateException(
                    "工具调用已达本次上限（" + s.max + " 次），" + tool + " 未执行。"
                    + "请不要再调用任何工具，立刻基于已有结果给出结论，"
                    + "并在「局限与提醒」里写清还有哪些数据没查、结论因此有什么边界。");
        }
    }
}
