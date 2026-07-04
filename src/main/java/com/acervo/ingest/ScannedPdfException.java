package com.acervo.ingest;

/**
 * Lançada quando um PDF parece ser escaneado (imagens em vez de texto).
 * O {@code AiFailureTranslator} traduz em uma mensagem amigável pro usuário.
 */
public class ScannedPdfException extends RuntimeException {

    public ScannedPdfException(String message) {
        super(message);
    }
}
