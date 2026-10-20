package com.example.rag.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public RagService(ChatClient.Builder builder, VectorStore vectorStore) {
        this.chatClient = builder.build();
        this.vectorStore = vectorStore;
    }

    /** 检索增强：先去向量库捞相关片段，拼进提示词，再让模型基于资料作答 */
    public Flux<String> ask(String question) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(4).build());
        String context = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        String prompt = """
                你是知识库问答助手。请只依据下面提供的「上下文」回答问题。
                如果上下文里没有相关信息，直接说"资料里没有提到"，不要编造。

                ===== 上下文 =====
                %s

                ===== 问题 =====
                %s

                请用简洁的中文回答。
                """.formatted(context, question);

        // stream() 返回 Flux<String>，配合 SSE 实现逐字输出
        return chatClient.prompt()
                .user(prompt)
                .stream()
                .content();
    }
}
