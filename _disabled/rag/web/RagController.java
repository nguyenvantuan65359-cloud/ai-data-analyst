package com.example.rag.web;

import com.example.rag.service.IngestionService;
import com.example.rag.service.RagService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
public class RagController {

    private final RagService ragService;
    private final IngestionService ingestionService;

    public RagController(RagService ragService, IngestionService ingestionService) {
        this.ragService = ragService;
        this.ingestionService = ingestionService;
    }

    /** 问答接口：流式返回（SSE），前端逐字显示 */
    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> ask(@RequestParam String q) {
        return ragService.ask(q);
    }

    /** 灌库接口：把 PDF 向量化进知识库。不传 path 用 application.yml 里的默认路径 */
    @PostMapping("/ingest")
    public String ingest(@RequestParam(required = false) String path) {
        int n = (path == null || path.isBlank())
                ? ingestionService.ingestDefault()
                : ingestionService.ingest(path);
        return "已向量化 " + n + " 个片段";
    }
}
