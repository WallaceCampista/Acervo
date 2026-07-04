package com.acervo.service;

import com.acervo.domain.Document;
import com.acervo.domain.Subject;
import com.acervo.rag.DocumentUploadedEvent;
import com.acervo.repository.DocumentRepository;
import com.acervo.repository.SubjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final Set<String> ALLOWED = Set.of("PDF", "DOCX", "PPTX", "TXT", "MD");
    private static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;
    private static final int MAX_NAME_LEN = 200;
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[\\p{Cntrl}<>\"'`\\\\/|?*\\u0000]");

    private final DocumentRepository documents;
    private final SubjectRepository subjects;
    private final ApplicationEventPublisher events;

    @Value("${acervo.storage.upload-dir}")
    private String uploadDir;

    public List<Document> listBySubject(UUID subjectId) {
        return documents.findBySubject_IdOrderByUploadedAtDesc(subjectId);
    }

    public List<Document> listBySubjectForOwner(UUID subjectId, UUID ownerId) {
        return documents.findBySubject_IdAndSubject_Owner_IdOrderByUploadedAtDesc(
                subjectId, ownerId);
    }

    @Transactional
    public Document upload(UUID subjectId, UUID ownerId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Arquivo vazio");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("Arquivo excede 50 MB");
        }

        Subject subject = subjects.findByIdAndOwner_Id(subjectId, ownerId)
                .orElseThrow(() -> new IllegalStateException(
                        "Matéria não acessível: " + subjectId));
        String rawName = Optional.ofNullable(file.getOriginalFilename()).orElse("arquivo");
        String safeName = sanitizeName(rawName);
        String ext = extOf(safeName).toUpperCase(Locale.ROOT);
        if (!ALLOWED.contains(ext)) {
            throw new IllegalArgumentException("Extensão não suportada: " + ext);
        }

        Path baseDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path dir = baseDir.resolve(subjectId.toString()).normalize();
        if (!dir.startsWith(baseDir)) {
            throw new IllegalStateException("Diretório de upload inválido");
        }
        Files.createDirectories(dir);

        String storedName = UUID.randomUUID() + "." + ext.toLowerCase(Locale.ROOT);
        Path target = dir.resolve(storedName).normalize();
        if (!target.startsWith(dir)) {
            throw new IllegalStateException("Caminho de arquivo inválido");
        }
        file.transferTo(target);

        Document doc = Document.builder()
                .subject(subject)
                .originalName(safeName)
                .storedPath(target.toString())
                .extension(ext)
                .sizeBytes(file.getSize())
                .status(Document.Status.PROCESSING)
                .build();
        doc = documents.save(doc);

        // Publica o evento. O EmbeddingPipeline ouve com AFTER_COMMIT, então
        // o pipeline assíncrono só começa depois que esta transação commitar.
        events.publishEvent(new DocumentUploadedEvent(doc.getId()));
        return doc;
    }

    @Transactional
    public void rename(UUID id, String newName, UUID ownerId) {
        Document doc = documents.findById(id).orElseThrow();
        ensureOwner(doc, ownerId);
        doc.setOriginalName(sanitizeName(newName));
    }

    @Transactional
    public void delete(UUID id, UUID ownerId) {
        Document doc = documents.findById(id).orElseThrow();
        ensureOwner(doc, ownerId);
        documents.delete(doc);
    }

    @Transactional(readOnly = true)
    public Document getForOwner(UUID id, UUID ownerId) {
        Document doc = documents.findById(id).orElseThrow();
        ensureOwner(doc, ownerId);
        return doc;
    }

    private void ensureOwner(Document doc, UUID ownerId) {
        Subject s = doc.getSubject();
        if (s == null || s.getOwner() == null
                || !s.getOwner().getId().equals(ownerId)) {
            throw new IllegalStateException(
                    "Documento não pertence ao usuário corrente");
        }
    }

    private String sanitizeName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) trimmed = "arquivo";
        String base = Path.of(trimmed).getFileName().toString();
        String cleaned = UNSAFE_CHARS.matcher(base).replaceAll("_");
        if (cleaned.length() > MAX_NAME_LEN) {
            cleaned = cleaned.substring(0, MAX_NAME_LEN);
        }
        return cleaned;
    }

    private String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "TXT" : name.substring(dot + 1);
    }
}
