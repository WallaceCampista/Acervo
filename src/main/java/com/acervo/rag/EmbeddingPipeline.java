package com.acervo.rag;

import com.acervo.domain.Chunk;
import com.acervo.domain.Document;
import com.acervo.ingest.Chunker;
import com.acervo.ingest.DocxExtractor;
import com.acervo.ingest.PdfExtractor;
import com.acervo.ingest.PlainTextExtractor;
import com.acervo.ingest.PptxExtractor;
import com.acervo.ingest.TextExtractor;
import com.acervo.repository.ChunkRepository;
import com.acervo.repository.DocumentRepository;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Extrai → chunka → persiste chunks → calcula embeddings → grava no VectorStore.
 * Roda assíncrono: o controller de upload retorna imediatamente.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingPipeline {

    private final PdfExtractor pdfExtractor;
    private final DocxExtractor docxExtractor;
    private final PptxExtractor pptxExtractor;
    private final PlainTextExtractor plainTextExtractor;
    private final Chunker chunker;
    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final VectorStore vectorStore;
    private final AiFailureTranslator failureTranslator;

    /**
     * Ouvinte do evento de upload. AFTER_COMMIT garante que o INSERT do documento
     * já foi commitado quando esta thread tenta lê-lo — evita a race condition
     * em que o @Async começava antes do commit da transação de upload.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        process(event.documentId());
    }

    /**
     * Reprocessa um documento que falhou. Apaga chunks (e respectivas entradas
     * no pgvector) antes de rodar o pipeline de novo para evitar duplicação.
     */
    @Async
    public void reindex(UUID documentId) {
        cleanupExisting(documentId);
        process(documentId);
    }

    @Transactional
    public void cleanupExisting(UUID documentId) {
        List<Chunk> existing = chunkRepository.findByDocument_Id(documentId);
        if (!existing.isEmpty()) {
            List<String> ids = existing.stream().map(c -> c.getId().toString()).toList();
            try {
                vectorStore.delete(ids);
            } catch (Exception e) {
                log.warn("Falha ao apagar vetores do documento {} antes do retry: {}",
                        documentId, e.getMessage());
            }
            chunkRepository.deleteByDocument_Id(documentId);
        }
    }

    @Timed(value = "acervo.embedding.process", description = "Tempo de extrair + chunkar + embeddar + indexar um documento")
    @Transactional
    public void process(UUID documentId) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            log.warn("Documento {} não encontrado ao tentar indexar — abortando.", documentId);
            return;
        }
        try {
            TextExtractor extractor = pickExtractor(doc.getExtension());
            List<TextExtractor.Page> pages = extractor.extract(Path.of(doc.getStoredPath()));
            List<Chunker.Chunk> pieces = chunker.split(pages);

            List<org.springframework.ai.document.Document> aiDocs = new ArrayList<>();
            int ord = 0;
            for (Chunker.Chunk piece : pieces) {
                Chunk c = Chunk.builder()
                        .document(doc)
                        .ordinal(ord++)
                        .content(piece.content())
                        .pageLabel(piece.pageLabel())
                        .tokenCount(piece.tokenCount())
                        .build();
                chunkRepository.save(c);

                Map<String, Object> meta = new HashMap<>();
                meta.put("subjectId", doc.getSubject().getId().toString());
                meta.put("documentId", doc.getId().toString());
                meta.put("documentName", doc.getOriginalName());
                meta.put("extension", doc.getExtension());
                meta.put("chunkId", c.getId().toString());
                meta.put("pageLabel", c.getPageLabel());
                aiDocs.add(new org.springframework.ai.document.Document(
                        c.getId().toString(), piece.content(), meta));
            }

            vectorStore.add(aiDocs);
            doc.setPages(pages.size());
            if (skipIfCancelled(documentId, "INDEXED")) return;
            doc.setStatus(Document.Status.INDEXED);
            doc.setProcessedAt(java.time.OffsetDateTime.now());
            documentRepository.save(doc);
            log.info("Documento {} indexado com {} chunks", doc.getOriginalName(), pieces.size());
        } catch (Exception e) {
            log.error("Falha ao indexar documento {}", documentId, e);
            if (skipIfCancelled(documentId, "FAILED")) return;
            doc.setStatus(Document.Status.FAILED);
            doc.setFailureReason(failureTranslator.translate(e));
            doc.setProcessedAt(java.time.OffsetDateTime.now());
            documentRepository.save(doc);
        }
    }

    private boolean skipIfCancelled(UUID id, String wouldBe) {
        Document cur = documentRepository.findById(id).orElse(null);
        if (cur != null && cur.getStatus() == Document.Status.CANCELLED) {
            log.info("Documento {} foi cancelado pelo usuário — não sobrescrevendo (seria {}).",
                    id, wouldBe);
            return true;
        }
        return false;
    }

    private TextExtractor pickExtractor(String ext) {
        return switch (ext.toUpperCase(Locale.ROOT)) {
            case "PDF" -> pdfExtractor;
            case "DOCX" -> docxExtractor;
            case "PPTX" -> pptxExtractor;
            default -> plainTextExtractor;
        };
    }

}
