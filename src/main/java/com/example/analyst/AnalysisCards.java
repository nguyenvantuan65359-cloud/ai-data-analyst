package com.example.analyst;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 分析卡片：把每一次「问题 → SQL → 图表 → 结论」沉淀成可复用的资产。
 *
 * 没有它，每次对话都是一次性的：上个月做过的月度复盘，这个月要从头来过。
 * 有了卡片，第二次做同类分析的成本从"重新问一遍"降到"打开上次的卡片"。
 */
@Component
public class AnalysisCards {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnalysisCards.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final ObjectMapper om = new ObjectMapper();

    private final Path root;

    public AnalysisCards(@Value("${app.analyst.cards-dir:analysis_cards}") String dir) {
        this.root = Paths.get(dir);
    }

    /** 保存一张卡片，返回 id */
    public synchronized String save(String sessionId, String question, String answer,
                                    List<Map<String, Object>> charts) {
        String sid = DataIngestion.safeSid(sessionId);
        String id = LocalDateTime.now().format(STAMP) + "-" + Math.abs(question.hashCode() % 100000);
        try {
            Path dir = root.resolve(sid);
            Files.createDirectories(dir);
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("id", id);
            card.put("sessionId", sid);
            card.put("at", LocalDateTime.now().toString());
            card.put("question", question);
            card.put("answer", answer);
            card.put("charts", charts == null ? List.of() : charts);
            Files.writeString(dir.resolve(id + ".json"),
                    om.writerWithDefaultPrettyPrinter().writeValueAsString(card), StandardCharsets.UTF_8);
            prune(dir, 200);
        } catch (Exception e) {
            log.warn("分析卡片保存失败：{}", e.getMessage());
        }
        return id;
    }

    /** 某会话的卡片列表（按时间倒序） */
    public List<Map<String, Object>> list(String sessionId) {
        String sid = DataIngestion.safeSid(sessionId);
        Path dir = root.resolve(sid);
        if (!Files.isDirectory(dir)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.toString().endsWith(".json")).sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> card = om.readValue(Files.readString(p, StandardCharsets.UTF_8), Map.class);
                    Map<String, Object> brief = new LinkedHashMap<>();
                    brief.put("id", card.get("id"));
                    brief.put("at", card.get("at"));
                    brief.put("question", clip(String.valueOf(card.getOrDefault("question", ""))));
                    brief.put("charts", ((List<?>) card.getOrDefault("charts", List.of())).size());
                    out.add(brief);
                } catch (Exception ignored) {
                    // 单个坏卡片不影响列表
                }
            });
        } catch (Exception e) {
            log.warn("分析卡片列表读取失败：{}", e.getMessage());
        }
        return out;
    }

    /** 取单张卡片全文 */
    public Map<String, Object> get(String sessionId, String id) {
        String sid = DataIngestion.safeSid(sessionId);
        Path p = root.resolve(sid).resolve(id + ".json");
        if (!Files.exists(p)) throw new IllegalArgumentException("没有这张卡片：" + id);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> card = om.readValue(Files.readString(p, StandardCharsets.UTF_8), Map.class);
            return card;
        } catch (Exception e) {
            throw new IllegalArgumentException("卡片读取失败：" + e.getMessage());
        }
    }

    public void delete(String sessionId, String id) {
        try {
            Files.deleteIfExists(root.resolve(DataIngestion.safeSid(sessionId)).resolve(id + ".json"));
        } catch (Exception ignored) {
            // 删不掉就算了
        }
    }

    /** 每个会话最多保留最近 N 张，避免无限膨胀 */
    private void prune(Path dir, int keep) {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> all = s.filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.reverseOrder()).toList();
            for (int i = keep; i < all.size(); i++) Files.deleteIfExists(all.get(i));
        } catch (Exception ignored) {
            // 清理失败不影响主流程
        }
    }

    private String clip(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }
}
