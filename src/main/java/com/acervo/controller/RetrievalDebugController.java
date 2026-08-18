package com.acervo.controller;

import com.acervo.config.CurrentUser;
import com.acervo.rag.RetrievalEvalService;
import com.acervo.rag.RetrievalEvalService.EvalReport;
import com.acervo.rag.RetrievalEvalService.GoldenCase;
import com.acervo.rag.RetrievalEvalService.RetrievalPreview;
import com.acervo.service.SubjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Retrieval sem geração: inspecionar o que o LLM receberia, e medir isso
 * contra um gabarito. Ambas as rotas são somente leitura — não criam conversa,
 * mensagem nem métrica, e nunca chamam o modelo de chat.
 *
 * <p>Uso típico (sessão autenticada):
 * <pre>
 * GET  /api/retrieval/preview?subjectId={id}&amp;q=o+que+e+uniao
 * POST /api/retrieval/eval?subjectId={id}
 *      [{"question":"o que é união?","expectedSnippets":["A ∪ B"]}]
 * </pre>
 *
 * <p>O acesso à matéria é validado a cada chamada — sem isso, informar um
 * {@code subjectId} alheio devolveria o conteúdo dos documentos de outro
 * usuário.
 */
@RestController
@RequestMapping("/api/retrieval")
@RequiredArgsConstructor
public class RetrievalDebugController {

    /** Teto de casos por rodada — cada caso é um round-trip de embedding. */
    private static final int MAX_CASES = 200;

    private final RetrievalEvalService evalService;
    private final SubjectService subjectService;
    private final CurrentUser currentUser;

    @GetMapping("/preview")
    public RetrievalPreview preview(@RequestParam UUID subjectId,
                                    @RequestParam String q) {
        subjectService.findByIdForOwner(subjectId, currentUser.id());
        if (q.isBlank()) {
            throw new IllegalArgumentException("Parâmetro 'q' não pode ser vazio.");
        }
        return evalService.preview(subjectId, q);
    }

    @PostMapping("/eval")
    public EvalReport eval(@RequestParam UUID subjectId,
                           @RequestBody List<GoldenCase> cases) {
        subjectService.findByIdForOwner(subjectId, currentUser.id());
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("Envie ao menos um caso no gabarito.");
        }
        if (cases.size() > MAX_CASES) {
            throw new IllegalArgumentException(
                    "Máximo de " + MAX_CASES + " casos por rodada.");
        }
        boolean anyBlank = cases.stream()
                .anyMatch(c -> c.question() == null || c.question().isBlank());
        if (anyBlank) {
            throw new IllegalArgumentException("Todo caso precisa de 'question'.");
        }
        return evalService.evaluate(subjectId, cases);
    }

    /**
     * Matéria inexistente ou de outro dono vira 404 — mesmo status pros dois
     * casos, pra não confirmar a existência de um id alheio.
     */
    @ExceptionHandler(SubjectService.SubjectAccessDeniedException.class)
    public ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Matéria não encontrada."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
