package com.acervo.controller;

import com.acervo.ai.AiGenerationService;
import com.acervo.config.CurrentUser;
import com.acervo.domain.StudySession;
import com.acervo.domain.Subject;
import com.acervo.service.StudyService;
import com.acervo.service.SubjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class StudyController {

    private final StudyService studyService;
    private final SubjectService subjectService;
    private final CurrentUser currentUser;

    @GetMapping("/subjects/{id}/study")
    public String view(@PathVariable UUID id,
                       @RequestParam(required = false) UUID session,
                       Model model) {
        UUID userId = currentUser.id();
        Subject subject = subjectService.findByIdForOwner(id, userId);

        StudySession active = null;
        if (session != null) {
            try {
                StudySession candidate = studyService.get(session);
                if (candidate.getSubject().getId().equals(id)
                        && candidate.getUserId().equals(userId)) {
                    active = candidate;
                }
            } catch (Exception ignored) {}
        }
        model.addAttribute("subjects", subjectService.findAllForOwner(userId));
        model.addAttribute("subject", subject);
        model.addAttribute("selected", subject);
        model.addAttribute("studySession", active);
        model.addAttribute("sessions", studyService.listForUser(id, userId));
        model.addAttribute("nav", "tools");
        model.addAttribute("tool", "study");
        return "study";
    }

    @PostMapping("/subjects/{id}/study/start")
    public String start(@PathVariable UUID id, Model model) {
        UUID userId = currentUser.id();
        subjectService.findByIdForOwner(id, userId);
        try {
            StudySession session = studyService.start(id, userId);
            return "redirect:/subjects/" + id + "/study?session=" + session.getId();
        } catch (AiGenerationService.AiGenerationException e) {
            return "redirect:/subjects/" + id + "/study?error=ai";
        }
    }

    @PostMapping("/study/{sessionId}/respond")
    public String respond(@PathVariable UUID sessionId,
                          @RequestParam String answer) {
        StudySession s = studyService.get(sessionId);
        // checagem de posse
        if (!s.getUserId().equals(currentUser.id())) {
            return "redirect:/inicio";
        }
        try {
            studyService.respond(sessionId, answer);
        } catch (AiGenerationService.AiGenerationException | IllegalStateException ignored) {}
        return "redirect:/subjects/" + s.getSubject().getId()
                + "/study?session=" + sessionId;
    }

    @PostMapping("/study/{sessionId}/finish")
    public String finish(@PathVariable UUID sessionId) {
        StudySession s = studyService.get(sessionId);
        if (!s.getUserId().equals(currentUser.id())) {
            return "redirect:/inicio";
        }
        studyService.finish(sessionId);
        return "redirect:/subjects/" + s.getSubject().getId() + "/study";
    }
}
