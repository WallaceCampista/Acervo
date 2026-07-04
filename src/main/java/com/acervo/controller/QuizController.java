package com.acervo.controller;

import com.acervo.ai.AiGenerationService;
import com.acervo.config.CurrentUser;
import com.acervo.domain.QuizQuestion;
import com.acervo.domain.Subject;
import com.acervo.service.QuizService;
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
public class QuizController {

    private final QuizService quizService;
    private final SubjectService subjectService;
    private final CurrentUser currentUser;

    @GetMapping("/subjects/{id}/quiz")
    public String view(@PathVariable UUID id, Model model) {
        UUID userId = currentUser.id();
        Subject subject = subjectService.findByIdForOwner(id, userId);
        List<QuizQuestion> questions = quizService.list(id);

        model.addAttribute("subjects", subjectService.findAllForOwner(userId));
        model.addAttribute("subject", subject);
        model.addAttribute("selected", subject);
        model.addAttribute("questions", questions);
        model.addAttribute("nav", "tools");
        model.addAttribute("tool", "quiz");
        return "quiz";
    }

    @PostMapping("/subjects/{id}/quiz/generate")
    public String generate(@PathVariable UUID id,
                           @RequestParam(defaultValue = "10") int count,
                           @RequestParam(defaultValue = "MEDIUM") String difficulty) {
        UUID userId = currentUser.id();
        subjectService.findByIdForOwner(id, userId);
        QuizQuestion.Difficulty d;
        try { d = QuizQuestion.Difficulty.valueOf(difficulty.toUpperCase()); }
        catch (Exception e) { d = QuizQuestion.Difficulty.MEDIUM; }
        try {
            quizService.generate(id, Math.min(30, Math.max(1, count)), d);
        } catch (AiGenerationService.AiGenerationException
                 | IllegalStateException ignored) {
        }
        return "redirect:/subjects/" + id + "/quiz";
    }

    /**
     * Endpoint AJAX para validar resposta. Retorna se está certa + explicação.
     */
    @PostMapping(value = "/quiz/{id}/answer",
                 produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> answer(@PathVariable UUID id,
                                                      @RequestParam int selected) {
        QuizQuestion q = quizService.get(id);
        boolean correct = q.getCorrectIndex() == selected;
        return ResponseEntity.ok(Map.of(
                "correct", correct,
                "correctIndex", q.getCorrectIndex(),
                "explanation", q.getExplanation() == null ? "" : q.getExplanation(),
                "source", q.getSourceDoc() == null ? "" : q.getSourceDoc()));
    }
}
