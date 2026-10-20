package com.example.analyst;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 业务口径规则：人工维护的一份"哪些数据算进来"的约定，每次问答都强制注入提示词。
 *
 * 为什么需要它：像"活动只统计进行中+报名成功待开始的，排除已退出/已结束/已售罄"这类口径，
 * 靠 LLM 每次临场复述必然时灵时不灵，而且错了很难被发现。
 * 写死一次，之后每次分析都带着——这是"能演示"和"能反复用"的分水岭。
 */
@Component
public class BusinessRules {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BusinessRules.class);

    private final Path file;
    private volatile String text = "";

    public BusinessRules(@Value("${app.analyst.rules-file:business_rules.md}") String file) {
        this.file = Paths.get(file);
        try {
            if (Files.exists(this.file)) this.text = Files.readString(this.file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("口径规则文件读取失败：{}", e.getMessage());
        }
    }

    public String current() { return text; }

    public boolean configured() { return text != null && !text.isBlank(); }

    /** 覆盖保存；返回带时间戳的回执 */
    public Map<String, Object> save(String content) {
        String body = content == null ? "" : content.strip();
        text = body;
        try {
            Files.writeString(file, body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("口径规则保存失败：{}", e.getMessage());
            throw new IllegalStateException("保存失败：" + e.getMessage());
        }
        return Map.of("status", "ok", "length", body.length(), "at", LocalDateTime.now().toString());
    }
}
