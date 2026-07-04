package com.acervo.controller;

import com.acervo.config.CurrentUser;
import com.acervo.domain.Document;
import com.acervo.domain.Subject;
import com.acervo.rag.EmbeddingPipeline;
import com.acervo.repository.DocumentRepository;
import com.acervo.service.DocumentService;
import com.acervo.service.SubjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/import")
@RequiredArgsConstructor
public class ImportController {

    private final SubjectService subjectService;
    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final EmbeddingPipeline embeddingPipeline;
    private final CurrentUser currentUser;

    @GetMapping
    public String view(@RequestParam(required = false) UUID subject,
                       @RequestParam(defaultValue = "list") String view,
                       Model model) {
        UUID userId = currentUser.id();
        List<Subject> subjects = subjectService.findAllForOwner(userId);
        Subject selected = subjects.stream()
                .filter(s -> subject == null || s.getId().equals(subject))
                .findFirst().orElse(subjects.isEmpty() ? null : subjects.get(0));

        model.addAttribute("subjects", subjects);
        model.addAttribute("selected", selected);
        model.addAttribute("view", view);
        model.addAttribute("nav", "import");
        if (selected != null) {
            var docs = documentService.listBySubjectForOwner(selected.getId(), userId);
            long total = docs.size();
            long processing = docs.stream()
                    .filter(d -> d.getStatus() == Document.Status.PROCESSING).count();
            long indexed = docs.stream()
                    .filter(d -> d.getStatus() == Document.Status.INDEXED).count();
            long failed = docs.stream()
                    .filter(d -> d.getStatus() == Document.Status.FAILED).count();
            model.addAttribute("documents", docs);
            model.addAttribute("docCount", subjectService.documentCount(selected.getId()));
            model.addAttribute("chunkCount", subjectService.chunkCount(selected.getId()));
            model.addAttribute("totalDocs", total);
            model.addAttribute("processingDocs", processing);
            model.addAttribute("indexedDocs", indexed);
            model.addAttribute("failedDocs", failed);
        }
        return "import";
    }

    @PostMapping("/{subjectId}/upload")
    public String upload(@PathVariable UUID subjectId,
                         @RequestParam("files") MultipartFile[] files) throws IOException {
        UUID userId = currentUser.id();
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                documentService.upload(subjectId, userId, file);
            }
        }
        return "redirect:/import?subject=" + subjectId;
    }

    @PostMapping("/documents/{id}/delete")
    public String delete(@PathVariable UUID id, @RequestParam UUID subjectId) {
        documentService.delete(id, currentUser.id());
        return "redirect:/import?subject=" + subjectId;
    }

    @PostMapping("/documents/{id}/rename")
    public String rename(@PathVariable UUID id,
                         @RequestParam UUID subjectId,
                         @RequestParam String name) {
        documentService.rename(id, name, currentUser.id());
        return "redirect:/import?subject=" + subjectId;
    }

    @PostMapping("/documents/{id}/retry")
    @Transactional
    public ResponseEntity<Map<String, Object>> retry(@PathVariable UUID id) {
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) {
            return ResponseEntity.notFound().build();
        }
        if (!ownedBy(doc, currentUser.id())) {
            return ResponseEntity.status(403).build();
        }
        UUID subjectId = doc.getSubject().getId();
        long processing = documentRepository.countBySubject_IdAndStatus(
                subjectId, Document.Status.PROCESSING);
        if (processing > 0) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Há documentos sendo indexados. Aguarde para tentar de novo."));
        }
        if (doc.getStatus() != Document.Status.FAILED
                && doc.getStatus() != Document.Status.CANCELLED) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Documento não está em estado de falha ou cancelado."));
        }
        doc.setStatus(Document.Status.PROCESSING);
        doc.setFailureReason(null);
        doc.setUploadedAt(java.time.OffsetDateTime.now());
        doc.setProcessedAt(null);
        documentRepository.save(doc);
        embeddingPipeline.reindex(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping(value = "/{subjectId}/status", produces = "application/json")
    @ResponseBody
    public Map<String, Object> status(@PathVariable UUID subjectId) {
        UUID userId = currentUser.id();
        // Garante posse — devolve estrutura vazia se a matéria não for do user
        if (subjectService.findByIdForOwnerOpt(subjectId, userId).isEmpty()) {
            return Map.of("error", "forbidden");
        }
        List<Document> docs = documentService.listBySubjectForOwner(subjectId, userId);
        long total = docs.size();
        long processing = docs.stream()
                .filter(d -> d.getStatus() == Document.Status.PROCESSING).count();
        long indexed = docs.stream()
                .filter(d -> d.getStatus() == Document.Status.INDEXED).count();
        long failed = docs.stream()
                .filter(d -> d.getStatus() == Document.Status.FAILED).count();

        List<Map<String, Object>> docList = docs.stream().map(d -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", d.getId().toString());
            m.put("status", d.getStatus().name());
            m.put("failureReason", d.getFailureReason());
            m.put("pages", d.getPages());
            m.put("uploadedAt", d.getUploadedAt().toInstant().toEpochMilli());
            m.put("processedAt", d.getProcessedAt() != null
                    ? d.getProcessedAt().toInstant().toEpochMilli() : null);
            return m;
        }).toList();

        double avgSeconds = docs.stream()
                .filter(d -> d.getStatus() == Document.Status.INDEXED
                        && d.getProcessedAt() != null)
                .mapToDouble(d -> java.time.Duration.between(
                        d.getUploadedAt(), d.getProcessedAt()).toMillis() / 1000.0)
                .average().orElse(0.0);

        long docCount = subjectService.documentCount(subjectId);
        long chunkCount = subjectService.chunkCount(subjectId);

        Map<String, Object> out = new HashMap<>();
        out.put("totalDocs", total);
        out.put("processingDocs", processing);
        out.put("indexedDocs", indexed);
        out.put("failedDocs", failed);
        out.put("docCount", docCount);
        out.put("chunkCount", chunkCount);
        out.put("avgSeconds", avgSeconds);
        out.put("documents", docList);
        return out;
    }

    @PostMapping("/documents/{id}/cancel")
    @Transactional
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable UUID id) {
        Document doc = documentRepository.findById(id).orElse(null);
        if (doc == null) return ResponseEntity.notFound().build();
        if (!ownedBy(doc, currentUser.id())) return ResponseEntity.status(403).build();
        if (doc.getStatus() != Document.Status.PROCESSING) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Documento não está sendo processado."));
        }
        doc.setStatus(Document.Status.CANCELLED);
        doc.setFailureReason("Cancelado pelo usuário.");
        doc.setProcessedAt(java.time.OffsetDateTime.now());
        documentRepository.save(doc);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static boolean ownedBy(Document doc, UUID ownerId) {
        return doc.getSubject() != null
                && doc.getSubject().getOwner() != null
                && doc.getSubject().getOwner().getId().equals(ownerId);
    }
}
