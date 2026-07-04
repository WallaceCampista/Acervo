package com.acervo.ai;

import com.acervo.rag.AiFailureTranslator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Wrapper minimalista em torno do {@link ChatModel} pra usos não-RAG
 * (sumarização, geração de flashcards, quiz, mapa de tópicos). Encapsula a
 * montagem do {@link Prompt} e a tradução de erros via {@link AiFailureTranslator}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiGenerationService {

    private final ChatModel chatModel;
    private final AiFailureTranslator failureTranslator;

    /**
     * Chama o LLM com um system prompt + user prompt e devolve o texto da
     * resposta. Lança {@link AiGenerationException} com mensagem amigável
     * em caso de falha — caller decide se mostra ao usuário.
     */
    public String generate(String systemPrompt, String userPrompt) {
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)));
            return chatModel.call(prompt).getResult().getOutput().getContent();
        } catch (Exception e) {
            String friendly = failureTranslator.translate(e);
            log.warn("Falha em geração LLM ({}): {}", e.getClass().getSimpleName(), friendly);
            throw new AiGenerationException(friendly, e);
        }
    }

    /**
     * Variante em streaming — devolve um {@link Flux} de deltas de token. A
     * tradução de erro é feita no callback do caller (o Flux propaga o
     * throwable original). Útil pra UIs que precisam de progresso ao vivo,
     * como a sumarização longa de documentos grandes.
     */
    public Flux<String> streamGenerate(String systemPrompt, String userPrompt) {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)));
        return chatModel.stream(prompt).map(this::extractDelta);
    }

    private String extractDelta(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null
                || chunk.getResult().getOutput() == null) return "";
        String c = chunk.getResult().getOutput().getContent();
        return c == null ? "" : c;
    }

    public static class AiGenerationException extends RuntimeException {
        public AiGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
