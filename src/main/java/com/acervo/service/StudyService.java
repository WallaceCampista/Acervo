package com.acervo.service;

import com.acervo.ai.AiGenerationService;
import com.acervo.domain.Chunk;
import com.acervo.domain.StudySession;
import com.acervo.domain.StudyTurn;
import com.acervo.domain.Subject;
import com.acervo.repository.ChunkRepository;
import com.acervo.repository.StudySessionRepository;
import com.acervo.repository.SubjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StudyService {

    private static final String SYSTEM_PROMPT = """
            Você é o Acervo em modo "tutor socrático". Conduza o aluno por uma
            sessão de estudo da matéria. Regras:
            - Você faz as perguntas (UMA por turno), não responde.
            - As perguntas saem do material fornecido — não invente.
            - Quando o aluno responder, AVALIE com 1 frase curta (acertou,
              parcial, errou) e EXPLIQUE rapidamente; depois faça a próxima
              pergunta, idealmente em outro tópico ou aprofundando.
            - Encerre se o aluno pedir.

            Saída exata (UMA linha por seção):
            AVALIACAO: <feedback do que foi respondido — pode ser "Início da sessão" no primeiro turno>
            PROXIMA: <a próxima pergunta>
            """;

    private final StudySessionRepository sessions;
    private final SubjectRepository subjects;
    private final ChunkRepository chunks;
    private final AiGenerationService ai;

    public StudySession get(UUID id) {
        return sessions.findById(id).orElseThrow();
    }

    public List<StudySession> listForUser(UUID subjectId, UUID userId) {
        return sessions.findBySubject_IdAndUserIdOrderByStartedAtDesc(subjectId, userId);
    }

    @Transactional
    public StudySession start(UUID subjectId, UUID userId) {
        Subject subject = subjects.findById(subjectId).orElseThrow();
        StudySession session = StudySession.builder()
                .subject(subject).userId(userId).build();
        session = sessions.save(session);
        // Primeira pergunta sem aluno ter falado ainda — usa entrada "vazia"
        appendTutor(session, askInitial(subject, ""));
        return sessions.save(session);
    }

    @Transactional
    public StudySession respond(UUID sessionId, String userAnswer) {
        StudySession session = sessions.findById(sessionId).orElseThrow();
        if (session.getStatus() == StudySession.Status.FINISHED) {
            throw new IllegalStateException("Sessão já encerrada.");
        }
        // Aluno fala
        StudyTurn aluno = StudyTurn.builder()
                .session(session).role(StudyTurn.Role.ALUNO)
                .content(userAnswer.trim()).build();
        session.getTurns().add(aluno);
        session.setTurnCount(session.getTurnCount() + 1);
        session.setLastActivityAt(OffsetDateTime.now());

        // Tutor avalia + pergunta de novo
        appendTutor(session, askNext(session.getSubject(), session));
        return sessions.save(session);
    }

    @Transactional
    public StudySession finish(UUID sessionId) {
        StudySession session = sessions.findById(sessionId).orElseThrow();
        session.setStatus(StudySession.Status.FINISHED);
        session.setLastActivityAt(OffsetDateTime.now());
        return sessions.save(session);
    }

    private void appendTutor(StudySession session, String content) {
        StudyTurn t = StudyTurn.builder()
                .session(session).role(StudyTurn.Role.TUTOR)
                .content(content).build();
        session.getTurns().add(t);
        session.setLastActivityAt(OffsetDateTime.now());
    }

    private String askInitial(Subject subject, String history) {
        List<Chunk> sample = sampleChunks(subject.getId(), 15);
        if (sample.isEmpty()) {
            return "AVALIACAO: Início da sessão.\nPROXIMA: A matéria ainda não tem documentos indexados. Importe materiais primeiro.";
        }
        return ai.generate(SYSTEM_PROMPT, """
                Matéria: %s
                Histórico: (sessão recém-iniciada)

                Material disponível:
                %s
                """.formatted(subject.getName(), buildBody(sample)));
    }

    private String askNext(Subject subject, StudySession session) {
        List<Chunk> sample = sampleChunks(subject.getId(), 15);
        if (sample.isEmpty()) {
            return "AVALIACAO: Sem material indexado.\nPROXIMA: Importe documentos pra continuar.";
        }
        StringBuilder history = new StringBuilder();
        for (StudyTurn t : session.getTurns()) {
            history.append(t.getRole() == StudyTurn.Role.TUTOR ? "Tutor: " : "Aluno: ")
                   .append(t.getContent()).append("\n");
        }
        return ai.generate(SYSTEM_PROMPT, """
                Matéria: %s

                Histórico da sessão:
                %s

                Material disponível:
                %s
                """.formatted(subject.getName(), history.toString(), buildBody(sample)));
    }

    private List<Chunk> sampleChunks(UUID subjectId, int max) {
        List<Chunk> all = new ArrayList<>(
                chunks.findAll().stream()
                        .filter(c -> c.getDocument().getSubject().getId().equals(subjectId))
                        .toList());
        Collections.shuffle(all);
        return all.size() > max ? all.subList(0, max) : all;
    }

    private String buildBody(List<Chunk> source) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < source.size(); i++) {
            Chunk c = source.get(i);
            sb.append("--- Trecho ").append(i + 1).append(" — ")
              .append(c.getDocument().getOriginalName()).append(" ---\n")
              .append(c.getContent()).append("\n\n");
        }
        return sb.toString();
    }
}
