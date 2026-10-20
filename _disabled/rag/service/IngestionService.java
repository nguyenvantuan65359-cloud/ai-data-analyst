package com.example.rag.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IngestionService {

    private final VectorStore vectorStore;
    // 每片约 512 token，重叠 100 token 保持上下文连贯
    private final TokenTextSplitter splitter = new TokenTextSplitter(512, 100, 5, 10000, true);

    @Value("${app.pdf.path}")
    private String defaultPdfPath;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /** 把一个 PDF 读出来 → 切片 → 向量化 → 写入向量库，返回切片数 */
    public int ingest(String path) {
        Resource resource = new FileSystemResource(path);
        PagePdfDocumentReader reader = new PagePdfDocumentReader(resource);
        List<Document> documents = reader.get();
        List<Document> chunks = splitter.apply(documents);
        vectorStore.add(chunks);
        return chunks.size();
    }

    public int ingestDefault() {
        return ingest(defaultPdfPath);
    }
}
