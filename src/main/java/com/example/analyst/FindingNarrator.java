package com.example.analyst;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 把体检发现翻译成人话——**只翻译，不算数、不解释因果**。
 *
 * 分工是有意的：判据、数字、反向检验全部由 {@link DiscoveryEngine} 算完，
 * 这个类拿不到原始数据，只拿到"已经算好的结论"，所以它没有机会自己算。
 * 这是它能被信任的前提——不是靠提示词要求它别算，是结构上不给它数据。
 *
 * 三道护栏：
 *   1. 出参是 JSON，按序号回填到对应发现，模型不能自己重排或增删条目；
 *   2. 解读文本里的数字必须能在报告里找到，找不到就整条丢弃（不显示、不猜测）；
 *   3. 提示词禁止写"因为…所以…"，只能写"需核对…"。因果不是它该下判断的事。
 */
@Component
public class FindingNarrator {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FindingNarrator.class);
    private static final int MAX_NOTES = 6;

    private final ChatClient chatClient;
    private final ObjectMapper om = new ObjectMapper();

    @Value("${app.analyst.plan-model:deepseek-chat}")
    private String model;

    public FindingNarrator(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /** 解读结果。summary 为空表示模型没给出可用内容，前端就不显示这一段 */
    public static class Narratives {
        public String summary = "";
        public List<Note> notes = new ArrayList<>();
        /** 被数字校验拦下的条数，前端可以据此提示"有 N 条解读被丢弃" */
        public int dropped;
    }

    public static class Note {
        /** 对应 findings 的下标（从 0 开始） */
        public int i;
        /** 这条发现意味着什么 */
        public String what = "";
        /** 下一步该核对什么 */
        public String check = "";
    }

    public Narratives narrate(ScanReport rep) {
        Narratives out = new Narratives();
        if (rep == null || rep.findings.isEmpty()) return out;

        String facts = factsBlock(rep);
        String system = """
                你是数据分析师的解读员。数据体检已经跑完，判据和数字都是算好的。
                你的任务只有一件：把每条发现翻译成业务语言，并指出下一步该核对什么。

                铁律（违反就不合格）：
                1. **不许出现新的数字。** 不许做差、算占比、算倍数、算排名变化。
                   要说量级就引用给到你的原值，不要自己加工。
                2. **不许断言因果。** 不能写"因为""导致""说明是XX造成"。
                   只能写"需核对…""这更可能是…（待验证）"。你没有外部信息，下不了因果判断。
                3. **不许引入报告之外的知识**（行业行情、政策、汇率、竞品）。你没有这些数据。
                4. what ≤ 60 字：这条发现意味着什么，用人话说，不要重复数字。
                   check ≤ 50 字：下一步查什么，要具体到一个可执行的动作或一张要看的表。
                5. 语气克制。不确定就说"样本不足，只能当线索"。
                5b. **统计状态决定措辞强度，不能越权**：
                    - 「显著」：可以说"确已变化"；
                    - 「未显著」：只能说"变化大但未达统计显著"，**不许说"异常""恶化""明显下滑"**；
                    - 「不可检验」：只能说"样本不足，只作描述"，**不许暗示它是异常**。
                    给到你的 p、q 值必须原样引用，不许改口径（q 是校正后的，比 p 更保守）。
                6. 若模式是「横截面」，**一个趋势词都不许出现**：上涨、下滑、增长、下降、走高、走低、
                   环比、同比、回暖、萎缩——**包括"不能读作增长"这类否定用法也别写**，
                   因为读者眼里只会留下"增长"两个字。要表达就用「高于同类期望 / 低于同类期望 / 偏离多少」。

                只输出 JSON，不要代码块，不要解释：
                {"summary":"两句话以内，这份数据最该关心什么","notes":[{"i":0,"what":"…","check":"…"}]}
                """;

        String raw;
        try {
            raw = chatClient.prompt().system(system).user(facts)
                    // Spring AI 2.0 的 options 接 Builder；解读不需要推理模型，用快模型省钱省时
                    .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                            .model(model).temperature(0.2))
                    .call().content();
        } catch (Exception e) {
            log.warn("体检解读调用失败（不影响发现本身）：{}", e.toString());
            return out;
        }

        // 数字素材池：报告里出现过的所有数字，解读文本只能引用这些
        String pool = pool(rep);
        try {
            JsonNode n = om.readTree(stripJson(raw));
            boolean crossMode = "B".equals(rep.mode);
            String summary = n.path("summary").asText("").trim();
            if (crossMode && trendWord(summary) != null) {
                out.dropped++;
                log.info("横截面解读 summary 出现趋势词「{}」，已丢弃：{}", trendWord(summary), summary);
                summary = "";
            }
            if (!summary.isBlank() && passes(summary, pool)) {
                out.summary = summary;
            } else if (!summary.isBlank()) {
                out.dropped++;
                log.info("体检解读 summary 含无出处数字，已丢弃：{}", summary);
            }
            for (JsonNode x : n.path("notes")) {
                Note note = new Note();
                note.i = x.path("i").asInt(-1);
                note.what = x.path("what").asText("").trim();
                note.check = x.path("check").asText("").trim();
                if (note.i < 0 || note.i >= rep.findings.size()) continue;
                if (note.what.isBlank() && note.check.isBlank()) continue;
                if (crossMode && (trendWord(note.what) != null || trendWord(note.check) != null)) {
                    out.dropped++;
                    log.info("横截面解读第 {} 条出现趋势词「{}」，已丢弃：{} / {}",
                            note.i + 1, trendWord(note.what) != null ? trendWord(note.what) : trendWord(note.check),
                            note.what, note.check);
                    continue;
                }
                if (!passes(note.what, pool) || !passes(note.check, pool)) {
                    out.dropped++;
                    log.info("体检解读第 {} 条含无出处数字，已丢弃：{} / {}", note.i + 1, note.what, note.check);
                    continue;
                }
                out.notes.add(note);
                if (out.notes.size() >= MAX_NOTES) break;
            }
        } catch (Exception e) {
            log.warn("体检解读 JSON 解析失败：{}，原文前 200 字：{}", e.toString(),
                    raw == null ? "" : raw.substring(0, Math.min(200, raw.length())));
        }
        return out;
    }

    /** 只喂已算好的结论，不喂原始数据——从结构上断掉"模型自己算"的可能 */
    private String factsBlock(ScanReport rep) {
        StringBuilder sb = new StringBuilder();
        sb.append("模式：").append(rep.modeLabel).append('\n');
        if ("B".equals(rep.mode)) {
            sb.append("注意：横截面模式没有时间基线，**不能说涨跌**，只能说相对同类的偏离。\n");
        }
        sb.append("主指标：").append(rep.metric).append("；可行维度：").append(String.join("、", rep.dims)).append('\n');
        sb.append("整体口径：").append(rep.headline).append('\n');
        if (!rep.notes.isEmpty()) sb.append("数据局限：").append(String.join("；", rep.notes)).append('\n');
        if (!rep.quality.isEmpty()) sb.append("数据质量：").append(String.join("；", rep.quality)).append('\n');

        sb.append("\n待解读的发现（序号从 0 开始）：\n");
        for (int i = 0; i < Math.min(MAX_NOTES, rep.findings.size()); i++) {
            ScanReport.Finding f = rep.findings.get(i);
            sb.append('[').append(i).append("] 切片=").append(f.slice)
              .append("；指标=").append(f.metric)
              .append("；判定=").append(f.kind)
              .append("；强度=").append(f.strength);
            if ("B".equals(rep.mode)) {
                sb.append(String.format("；实际=%s；同类期望=%s；O/E=%.2f；偏离贡献=%+.1f%%",
                        n(f.last), n(f.prev), f.oe, f.sharePct));
            } else {
                sb.append(String.format("；本期=%s；上期=%s；环比=%+.1f%%；3期累计超额=%s；稳健z=%.2f；对整体变化贡献=%+.1f%%",
                        n(f.last), n(f.prev), f.pct, n(f.excess3), f.z, f.sharePct));
            }
            sb.append("；样本n=").append(f.n);
            sb.append("；统计状态=").append(f.testStatus);
            if (!"不可检验".equals(f.testStatus)) {
                sb.append(String.format("（%s，p=%.4f，q=%.3f）", f.testMethod, f.pValue, f.qValue));
            } else {
                sb.append("（").append(f.skipReason).append("）");
            }
            if (f.reliability < 1) sb.append("；**样本不足，只能当线索**");
            if (f.peer != null && !f.peer.isBlank()) sb.append("；反向检验=").append(f.peer);
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 数字池：报告里出现过的所有数字，解读只能引用这些 */
    private String pool(ScanReport rep) {
        StringBuilder sb = new StringBuilder();
        sb.append(rep.growth * 100).append(' ').append(rep.totalDelta).append(' ')
          .append(rep.periodCount).append(' ').append(rep.slicesScanned).append(' ');
        for (ScanReport.Finding f : rep.findings) {
            sb.append(f.last).append(' ').append(f.prev).append(' ')
              .append(f.delta).append(' ').append(f.pct).append(' ')
              .append(f.excess).append(' ').append(f.excess3).append(' ')
              .append(f.z).append(' ').append(f.sharePct).append(' ')
              .append(f.oe).append(' ').append(f.n).append(' ')
              .append(f.pValue).append(' ').append(f.qValue).append(' ')
              .append(f.pValue * 100).append(' ').append(f.qValue * 100).append(' ');
        }
        return sb.toString();
    }

    /** 横截面模式下会误导的趋势词。渲染出去读者就会读成涨跌，所以直接丢 */
    private static final String[] TREND_WORDS = {
            "上涨", "下滑", "增长", "下降", "走高", "走低", "环比", "同比", "回暖", "萎缩"
    };

    private static String trendWord(String text) {
        if (text == null) return null;
        for (String w : TREND_WORDS) if (text.contains(w)) return w;
        return null;
    }

    /** 复用统一的出处校验：数字必须能在素材池里找到（允许四舍五入） */
    private boolean passes(String text, String pool) {
        if (text == null || text.isBlank()) return true;
        return GroundingCheck.check(text, pool).isEmpty();
    }

    private static String n(double v) {
        if (Math.abs(v) >= 10000) return String.format("%,.0f", v);
        if (Math.abs(v) >= 100) return String.format("%,.1f", v);
        return String.format("%.2f", v);
    }

    /** 模型有时会包一层 ```json，剥掉 */
    private static String stripJson(String s) {
        if (s == null) return "{}";
        String t = s.trim();
        if (t.startsWith("```")) {
            int i = t.indexOf('{');
            int j = t.lastIndexOf('}');
            if (i >= 0 && j > i) return t.substring(i, j + 1);
        }
        int i = t.indexOf('{');
        int j = t.lastIndexOf('}');
        return (i >= 0 && j > i) ? t.substring(i, j + 1) : t;
    }
}
