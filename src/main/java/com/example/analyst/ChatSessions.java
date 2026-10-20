package com.example.analyst;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮对话记忆：每个会话一个 ChatMemory（滑动窗口，保留最近若干条消息）。
 * 没有它，AI 每轮都失忆——用户说“那按地区再拆一次”，它不知道“那”指什么。
 */
@Component
public class ChatSessions {

    private final int maxMessages;
    private final Map<String, ChatMemory> memories = new ConcurrentHashMap<>();

    public ChatSessions(@org.springframework.beans.factory.annotation.Value("${app.analyst.chat-memory-messages:20}") int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public ChatMemory get(String sessionId) {
        return memories.computeIfAbsent(sessionId, id ->
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new InMemoryChatMemoryRepository())
                        .maxMessages(maxMessages)
                        .build());
    }

    public void clear(String sessionId) {
        ChatMemory m = memories.remove(sessionId);
        if (m != null) m.clear(sessionId);
    }

    public String describe(String sessionId) {
        ChatMemory m = memories.get(sessionId);
        return (m == null) ? "无历史" : (m.get(sessionId).size() + " 条历史消息");
    }
}
