package com.acervo.rag;

import java.util.UUID;

/** Disparado por DocumentService.upload após salvar o documento. */
public record DocumentUploadedEvent(UUID documentId) {}
