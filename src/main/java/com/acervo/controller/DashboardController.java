package com.acervo.controller;

import com.acervo.config.CurrentUser;
import com.acervo.repository.ConversationRepository;
import com.acervo.repository.DocumentRepository;
import com.acervo.service.FlashcardService;
import com.acervo.service.GapAnalysisService;
import com.acervo.service.SubjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final SubjectService subjectService;
    private final DocumentRepository documentRepository;
    private final ConversationRepository conversationRepository;
    private final FlashcardService flashcardService;
    private final GapAnalysisService gapAnalysisService;
    private final CurrentUser currentUser;

    @GetMapping("/inicio")
    public String view(Model model) {
        UUID userId = currentUser.id();
        var subjects = subjectService.findAllForOwner(userId);
        model.addAttribute("nav", "inicio");
        model.addAttribute("subjects", subjects);
        model.addAttribute("subjectCount", subjects.size());
        model.addAttribute("documentCount",
                documentRepository.countBySubject_Owner_Id(userId));
        model.addAttribute("conversationCount",
                conversationRepository.countBySubject_Owner_Id(userId));

        // Flashcards "due" por matéria + tópicos sugeridos pra revisão
        Map<UUID, Long> dueBySubject = new LinkedHashMap<>();
        Map<UUID, List<GapAnalysisService.TopicSuggestion>> topicsBySubject = new LinkedHashMap<>();
        long totalDue = 0;
        for (var s : subjects) {
            long due = flashcardService.countDue(s.getId());
            dueBySubject.put(s.getId(), due);
            totalDue += due;
            topicsBySubject.put(s.getId(),
                    gapAnalysisService.suggestForUser(userId, s.getId(), 5));
        }
        model.addAttribute("dueBySubject", dueBySubject);
        model.addAttribute("topicsBySubject", topicsBySubject);
        model.addAttribute("totalDueCards", totalDue);

        return "inicio";
    }
}
