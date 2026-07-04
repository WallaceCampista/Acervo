package com.acervo.controller;

import com.acervo.ai.AiGenerationService;
import com.acervo.config.CurrentUser;
import com.acervo.domain.DocumentSummary;
import com.acervo.service.DocumentService;
import com.acervo.service.SummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/import/documents")
@RequiredArgsConstructor
public class SummaryController {

    private final SummaryService summaryService;
    private final DocumentService documentService;
    private final CurrentUser currentUser;

    /**
     * Página dedicada de resumo — mostra os 3 níveis (gera sob demanda).
     */
    @GetMapping("/{id}/summary")
    public String view(@PathVariable UUID id, Model model) {
        var doc = documentService.getForOwner(id, currentUser.id());
        model.addAttribute("document", doc);
        model.addAttribute("summaries", summaryService.listForDocument(id));
        model.addAttribute("nav", "import");
        return "summary";
    }

    /**
     * Gera (ou regenera) o resumo no nível pedido. JSON pro frontend.
     */
    @PostMapping(value = "/{id}/summary/{level}",
                 produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generate(@PathVariable UUID id,
                                                        @PathVariable String level) {
        documentService.getForOwner(id, currentUser.id());
        DocumentSummary.Level parsed;
        try {
            parsed = DocumentSummary.Level.valueOf(level.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nível inválido"));
        }
        try {
            DocumentSummary s = summaryService.generate(id, parsed);
            return ResponseEntity.ok(Map.of(
                    "id", s.getId().toString(),
                    "level", s.getLevel().name(),
                    "content", s.getContent(),
                    "generatedAt", s.getGeneratedAt().toString()));
        } catch (AiGenerationService.AiGenerationException e) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Variante em streaming via SSE — pra UI da tela {@code /resumo} mostrar
     * progresso em tempo real (LM Studio leva ~40 s pra processar prompts de
     * 7 mil tokens). Eventos: {@code info}, {@code token}, {@code done},
     * {@code error}.
     */
    @GetMapping(value = "/{id}/summary/{level}/stream",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamSummary(@PathVariable UUID id, @PathVariable String level) {
        documentService.getForOwner(id, currentUser.id());
        DocumentSummary.Level parsed = DocumentSummary.Level.valueOf(level.toUpperCase());
        // 5 min de timeout — sumarização de docs grandes pode demorar.
        SseEmitter emitter = new SseEmitter(300_000L);
        summaryService.streamGenerate(id, parsed, emitter);
        return emitter;
    }
}
