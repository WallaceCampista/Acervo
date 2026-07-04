package com.acervo.service;

import com.acervo.ai.AiGenerationService;
import com.acervo.domain.Chunk;
import com.acervo.domain.Subject;
import com.acervo.repository.ChunkRepository;
import com.acervo.repository.SubjectRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Extrai um mapa hierárquico de tópicos da matéria via LLM. Resultado fica em
 * cache Caffeine (TTL 1h) — sem persistência por enquanto, já que mudanças nos
 * docs invalidam quase tudo e regenerar é barato.
 */
@Service
@RequiredArgsConstructor
public class TopicMapService {

    private static final String SYSTEM_PROMPT = """
            Você é o Acervo. Extraia um MAPA DE TÓPICOS hierárquico da matéria a
            partir do material fornecido. Saída em formato markdown indentado:
            - Tópico raiz
              - Sub-tópico
                - Detalhe importante
            Máximo 3 níveis. Cubra o material de forma equilibrada (sem
            concentrar em um único capítulo). Mantenha conciso (uma frase por
            tópico) e em português do Brasil.
            """;

    private final SubjectRepository subjects;
    private final ChunkRepository chunks;
    private final AiGenerationService ai;

    private final Cache<UUID, String> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(200)
            .build();

    public String getOrGenerate(UUID subjectId) {
        return cache.get(subjectId, this::generateNow);
    }

    public void invalidate(UUID subjectId) {
        cache.invalidate(subjectId);
    }

    private String generateNow(UUID subjectId) {
        Subject subject = subjects.findById(subjectId).orElseThrow();
        List<Chunk> sample = sampleChunks(subjectId, 30);
        if (sample.isEmpty()) return "- (matéria sem documentos indexados)";
        StringBuilder body = new StringBuilder();
        for (Chunk c : sample) {
            body.append("- ").append(c.getDocument().getOriginalName());
            if (c.getPageLabel() != null) body.append(" · ").append(c.getPageLabel());
            body.append(": ").append(truncate(c.getContent(), 240)).append("\n");
        }
        return ai.generate(SYSTEM_PROMPT, """
                Matéria: %s

                Trechos (alguns dos disponíveis):
                %s
                """.formatted(subject.getName(), body.toString()));
    }

    private List<Chunk> sampleChunks(UUID subjectId, int max) {
        List<Chunk> all = new ArrayList<>(
                chunks.findAll().stream()
                        .filter(c -> c.getDocument().getSubject().getId().equals(subjectId))
                        .toList());
        Collections.shuffle(all);
        return all.size() > max ? all.subList(0, max) : all;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
