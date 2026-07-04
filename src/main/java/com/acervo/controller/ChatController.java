package com.acervo.controller;

import com.acervo.config.CurrentUser;
import com.acervo.domain.Conversation;
import com.acervo.domain.Message;
import com.acervo.domain.Subject;
import com.acervo.rag.RagService;
import com.acervo.service.ConversationService;
import com.acervo.service.SubjectService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final SubjectService subjectService;
    private final ConversationService conversationService;
    private final RagService ragService;
    private final CurrentUser currentUser;

    private static final String LAST_CONV_ATTR_PREFIX = "acervo.lastConv:";

    @GetMapping
    public String view(@RequestParam(required = false) UUID subject,
                       @RequestParam(required = false) UUID conv,
                       HttpSession session,
                       Model model) {
        UUID userId = currentUser.id();
        List<Subject> subjects = subjectService.findAllForOwner(userId);
        if (subjects.isEmpty()) {
            return "redirect:/import";
        }

        Subject selected = subjects.stream()
                .filter(s -> subject == null || s.getId().equals(subject))
                .findFirst().orElse(subjects.get(0));

        List<Conversation> conversations = conversationService
                .listBySubjectForOwner(selected.getId(), userId);
        // Só seleciona uma conversa se o usuário pediu explicitamente via ?conv=
        // (ao clicar num item do histórico). Acessar /chat puro deve cair na
        // tela inicial do chat — nunca abrir a última conversa automaticamente.
        Conversation active = conv == null ? null
                : conversations.stream()
                        .filter(c -> c.getId().equals(conv))
                        .findFirst().orElse(null);

        String lastConvKey = LAST_CONV_ATTR_PREFIX + selected.getId();
        if (active != null) {
            session.setAttribute(lastConvKey, active.getId().toString());
        }
        Object lastVisitedRaw = session.getAttribute(lastConvKey);
        UUID lastVisitedConvId = null;
        if (lastVisitedRaw != null) {
            try {
                UUID parsed = UUID.fromString(lastVisitedRaw.toString());
                boolean stillExists = conversations.stream().anyMatch(c -> c.getId().equals(parsed));
                if (stillExists) lastVisitedConvId = parsed;
                else session.removeAttribute(lastConvKey);
            } catch (IllegalArgumentException ignored) {
                session.removeAttribute(lastConvKey);
            }
        }

        model.addAttribute("nav", "chat");
        model.addAttribute("subjects", subjects);
        model.addAttribute("selected", selected);
        model.addAttribute("conversations", conversations);
        model.addAttribute("active", active);
        model.addAttribute("lastVisitedConvId", lastVisitedConvId);
        return "chat";
    }

    @PostMapping("/conversations")
    public String newConversation(@RequestParam UUID subjectId) {
        Conversation c = conversationService.create(subjectId, currentUser.id());
        return "redirect:/chat?subject=" + subjectId + "&conv=" + c.getId();
    }

    @PostMapping("/conversations/{id}/delete")
    public String deleteConversation(@PathVariable UUID id, @RequestParam UUID subjectId) {
        conversationService.delete(id, currentUser.id());
        return "redirect:/chat?subject=" + subjectId;
    }

    /** Tamanho máximo da imagem anexada (10MB). */
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    /**
     * Envio tradicional (POST), usado quando há imagem anexada — o canal SSE
     * de streaming não carrega upload binário. Sem imagem, o front usa o
     * streaming via {@link #stream}; este endpoint continua servindo de
     * fallback para JS desabilitado.
     */
    @PostMapping("/conversations/{id}/messages")
    public String ask(@PathVariable UUID id,
                      @RequestParam UUID subjectId,
                      @RequestParam(required = false, defaultValue = "") String question,
                      @RequestParam(required = false) MultipartFile image) throws IOException {
        conversationService.getForOwner(id, currentUser.id()); // valida posse
        byte[] imageData = null;
        String imageMime = null;
        if (image != null && !image.isEmpty()) {
            imageMime = image.getContentType();
            if (imageMime == null || !imageMime.startsWith("image/")) {
                throw new IllegalArgumentException("O arquivo anexado não é uma imagem.");
            }
            if (image.getSize() > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("Imagem muito grande (máx. 10MB).");
            }
            imageData = image.getBytes();
        }
        ragService.answer(id, question, imageData, imageMime);
        return "redirect:/chat?subject=" + subjectId + "&conv=" + id;
    }

    /**
     * Serve a imagem anexada a uma mensagem. Acesso restrito ao dono da
     * conversa. Cacheável — bytes imutáveis após a criação da mensagem.
     */
    @GetMapping("/messages/{id}/image")
    public ResponseEntity<byte[]> messageImage(@PathVariable UUID id) {
        Message m = conversationService.getMessageForOwner(id, currentUser.id());
        if (m.getImageData() == null || m.getImageData().length == 0) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = m.getImageMime() != null
                ? MediaType.parseMediaType(m.getImageMime())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .body(m.getImageData());
    }

    /**
     * Resposta em streaming via SSE. Browser abre {@code EventSource} nesta
     * rota; o servidor envia eventos {@code message} (cada token), e ao final
     * {@code done} ou {@code error}. O método {@link RagService#answerStream}
     * é {@code @Async}, então a thread do Tomcat é liberada após o handshake.
     */
    @GetMapping(value = "/conversations/{id}/stream",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID id,
                             @RequestParam String question) {
        conversationService.getForOwner(id, currentUser.id());
        // 3 min de timeout — chats longos com modelos locais podem demorar.
        SseEmitter emitter = new SseEmitter(180_000L);
        ragService.answerStream(id, question, emitter);
        return emitter;
    }

    /**
     * Exporta a conversa como Markdown para download.
     */
    @GetMapping("/conversations/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID id,
                                         @RequestParam(defaultValue = "md") String format) {
        if (!"md".equalsIgnoreCase(format)) {
            return ResponseEntity.badRequest().build();
        }
        conversationService.getForOwner(id, currentUser.id());
        String md = conversationService.exportMarkdown(id);
        byte[] body = md.getBytes(StandardCharsets.UTF_8);
        String filename = "acervo-conversa-" + id.toString().substring(0, 8) + ".md";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .body(body);
    }
}
