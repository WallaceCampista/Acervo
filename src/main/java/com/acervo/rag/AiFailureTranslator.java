package com.acervo.rag;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traduz exceptions vindas do servidor de IA local (LM Studio via API
 * OpenAI-compat) em mensagens curtas em pt-BR para o usuário final.
 * Compartilhado por EmbeddingPipeline e RagService para não duplicar a regra
 * de detecção.
 */
@Component
public class AiFailureTranslator {

    private static final Pattern JSON_MESSAGE_FIELD =
            Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)+)\"");

    public String translate(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            // PDF escaneado: mensagem já vem amigável da própria exception.
            if (cur instanceof com.acervo.ingest.ScannedPdfException) {
                return cur.getMessage();
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String low = msg.toLowerCase(Locale.ROOT);

                // Modelo de IA inexistente/incompatível
                if (low.contains("model_not_found")
                        || low.contains("not supported for embedcontent")
                        || (low.contains("404") && low.contains("models/"))
                        || (low.contains("not_found") && low.contains("models/"))) {
                    return "Modelo de IA não encontrado no servidor local. "
                            + "Confirme se ele está carregado no LM Studio e se o nome "
                            + "configurado em application-dev.yml bate com o identificador exibido.";
                }

                // Rede / servidor de IA fora do ar
                if (low.contains("connection refused") || low.contains("connect timed out")
                        || low.contains("unknownhost") || low.contains("no route to host")
                        || low.contains("network is unreachable")) {
                    return "Servidor de IA local fora do ar. Confirme se o LM Studio "
                            + "está rodando em http://localhost:1234.";
                }

                // Erros gerais do provedor
                if (low.contains("503") || low.contains("unavailable")
                        || low.contains("server is overloaded")) {
                    return "Servidor de IA sobrecarregado no momento. "
                            + "Tente novamente em alguns instantes.";
                }
                if (low.contains("500") || low.contains("internal_server")) {
                    return "Erro interno do servidor de IA. Tente novamente em instantes.";
                }

                // PDF
                if (low.contains("invalidpdf") || low.contains("encrypted")
                        || low.contains("password")) {
                    return "PDF inválido ou protegido por senha — não foi possível extrair o texto.";
                }
            }
            cur = cur.getCause();
        }

        // Fallback: extrai só o campo "message" do JSON em vez do JSON inteiro.
        Throwable root = e;
        while (root.getCause() != null) root = root.getCause();
        String raw = root.getMessage();
        if (raw == null || raw.isBlank()) {
            return "Erro inesperado (" + root.getClass().getSimpleName() + ").";
        }
        String cleaned = extractJsonMessage(raw);
        if (cleaned.length() > 220) cleaned = cleaned.substring(0, 220) + "…";
        return cleaned;
    }

    private String extractJsonMessage(String raw) {
        Matcher m = JSON_MESSAGE_FIELD.matcher(raw);
        if (m.find()) {
            return m.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", " ")
                    .replace("\\t", " ")
                    .trim();
        }
        return raw;
    }
}
