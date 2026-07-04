package com.acervo.controller;

import com.acervo.ai.AiGenerationService;
import com.acervo.config.CurrentUser;
import com.acervo.domain.Flashcard;
import com.acervo.domain.Subject;
import com.acervo.service.FlashcardService;
import com.acervo.service.SubjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class FlashcardController {

    private final FlashcardService flashcardService;
    private final SubjectService subjectService;
    private final CurrentUser currentUser;

    /**
     * Lista flashcards pendentes (due) — UI estilo Anki.
     */
    @GetMapping("/subjects/{id}/flashcards")
    public String view(@PathVariable UUID id, Model model) {
        UUID userId = currentUser.id();
        Subject subject = subjectService.findByIdForOwner(id, userId);
        List<Flashcard> due = flashcardService.dueNow(id);
        List<Flashcard> all = flashcardService.list(id);

        model.addAttribute("subjects", subjectService.findAllForOwner(userId));
        model.addAttribute("subject", subject);
        model.addAttribute("selected", subject);
        model.addAttribute("dueCards", due);
        model.addAttribute("allCards", all);
        model.addAttribute("dueCount", due.size());
        model.addAttribute("totalCount", all.size());
        model.addAttribute("nav", "tools");
        model.addAttribute("tool", "flashcards");
        return "flashcards";
    }

    @PostMapping("/subjects/{id}/flashcards/generate")
    public String generate(@PathVariable UUID id,
                           @RequestParam(defaultValue = "20") int count,
                           Model model) {
        UUID userId = currentUser.id();
        subjectService.findByIdForOwner(id, userId);
        try {
            flashcardService.generate(id, Math.min(50, Math.max(1, count)));
        } catch (AiGenerationService.AiGenerationException
                 | IllegalStateException ignored) {
            // mensagem já tem; trate na view (queremos seguir pra mostrar resultado parcial)
        }
        return "redirect:/subjects/" + id + "/flashcards";
    }

    /**
     * Endpoint AJAX para registrar a revisão com quality 0–5 (SM-2).
     */
    @PostMapping(value = "/flashcards/{id}/review",
                 produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> review(@PathVariable UUID id,
                                                      @RequestParam int quality) {
        try {
            Flashcard f = flashcardService.review(id, quality);
            return ResponseEntity.ok(Map.of(
                    "intervalDays", f.getIntervalDays(),
                    "dueAt", f.getDueAt().toString(),
                    "repetitions", f.getRepetitions()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
