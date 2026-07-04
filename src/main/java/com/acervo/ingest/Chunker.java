package com.acervo.ingest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Chunking simples por aproximação de tokens (1 token ≈ 4 chars).
 * Mantém o label da página de origem para citação.
 */
@Component
public class Chunker {

    private final int chunkSize;
    private final int overlap;

    public Chunker(@Value("${acervo.rag.chunk-size:500}") int chunkSize,
                   @Value("${acervo.rag.chunk-overlap:80}") int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public record Chunk(String content, String pageLabel, int tokenCount) {}

    public List<Chunk> split(List<TextExtractor.Page> pages) {
        int charSize = chunkSize * 4;
        int charOverlap = overlap * 4;
        List<Chunk> out = new ArrayList<>();
        for (TextExtractor.Page p : pages) {
            String text = p.content();
            int i = 0;
            while (i < text.length()) {
                int end = Math.min(text.length(), i + charSize);
                String piece = text.substring(i, end);
                out.add(new Chunk(piece.trim(), p.label(), piece.length() / 4));
                if (end == text.length()) break;
                i = end - charOverlap;
            }
        }
        return out;
    }
}
