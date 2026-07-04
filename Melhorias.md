# Acervo — Roadmap de Melhorias

Próximos passos para evolução do sistema, organizados em **5 fases** com objetivos claros, entregas mensuráveis e dependências entre fases. Pode ser executado linearmente ou em paralelo (onde indicado).

> Cada fase tem **3 a 7 itens** com checkbox. Marque conforme avança. Os critérios de aceitação são o "definition of done" — sem eles a fase não está pronta, mesmo com os checkboxes marcados.

---

## Visão Geral das Fases

| # | Fase | Foco | Estimativa | Depende de |
|---|---|---|---|---|
| 1 | **Robustez e qualidade da v1** | Testes, código limpo, observabilidade | 1 sem | — |
| 2 | **RAG mais inteligente** | Reranker, hybrid search, OCR | 2 sem | Fase 1 (testes) |
| 3 | **UX e produtividade do estudante** | Streaming, atalhos, exportação | 1-2 sem | — (paralelo à 2) |
| 4 | **Multi-usuário** | Auth real, compartilhamento, perfis | 2-3 sem | Fase 1 |
| 5 | **Inteligência avançada** | Flashcards, sumarização, quiz | 2-3 sem | Fases 2 e 3 |

---

## Fase 1 — Robustez e qualidade da v1 ✅

**Objetivo:** fechar dívidas técnicas da v1, deixar o código testável e observável antes de empilhar features novas.

### Itens

- [x] **Testes de repositório com Testcontainers** — `SubjectRepositoryTest`, `DocumentRepositoryTest`, `ChunkRepositoryTest`, `ConversationRepositoryTest`, `MessageAndProfileRepositoryTest` sobem Postgres+pgvector em container e validam CRUD básico, queries customizadas e cascade deletes.
- [x] **Testes do `RagService`** — `RagServiceTest` com mocks de `VectorStore` e `ChatModel`. Cobre top-K, filtro por subjectId, persistência de citações, `responseTimeMs`, fallback sem retrieval, erro do LLM, chunkId inválido tolerado.
- [x] **Testes E2E do fluxo de chat** — `ChatFlowIntegrationTest` (5 testes): fluxo síncrono, streaming SSE, `SessionGuardFilter`, erros traduzidos.
- [x] **Testes do `EmbeddingPipeline`** — `EmbeddingPipelineTest`: caminho feliz (`PROCESSING → INDEXED`), falha (`PROCESSING → FAILED` com `failureReason` traduzida), `CANCELLED` não sobrescrito, doc inexistente tolerado.
- [x] **Remover HTMX morto do `layout.html`** — script removido; bundle 100% sem HTMX.
- [x] **Observabilidade básica** — `micrometer-registry-prometheus` adicionado, endpoint `/actuator/prometheus` exposto, `@Timed` em `RagService.answer`, `RagService.answerStream` e `EmbeddingPipeline.process`. Histogramas com percentis p50/p95/p99 configurados no `application.yml`.
- [x] **Health check melhor** — `LmStudioHealthIndicator` sonda `GET /v1/models` com timeout de 2s. Aparece em `/actuator/health/lmStudio` com `latencyMs` e detalhe do erro quando DOWN.

### Critérios de aceitação

- `mvn verify` roda os testes em CI e bloqueia merge em caso de falha.
- Cobertura mínima de 60% nas classes de `rag/`, `ingest/` e `service/` (medido com JaCoCo).
- Endpoint `/actuator/metrics` mostra latência do RAG por percentil.
- Zero HTMX no bundle (verificável por `grep -r htmx src/main/resources/templates`).

### Não objetivos

- Não buscar 100% de cobertura. Foco em RAG, ingestão e fluxos críticos.
- Não migrar pra outro framework de teste — JUnit 5 + Spring Boot Test bastam.

---

## Fase 2 — RAG mais inteligente ✅

**Objetivo:** melhorar a qualidade das respostas sem trocar o modelo. Recuperação melhor → contexto melhor → resposta melhor.

### Itens

- [x] **Reranker** — `MmrReranker` aplica Maximum Marginal Relevance sobre os candidatos (vetorial + lexical), balanceando relevância × diversidade (`λ=0.7` default, configurável via `acervo.rag.mmr-lambda`). Não é cross-encoder neural, mas é zero-dep e melhora real — sem precisar de modelo extra rodando.
- [x] **Hybrid search (FTS + RRF)** — Migration V10 com `chunk.content_tsv tsvector` gerada + índice GIN. `ChunkRepository.findTopByLexicalSearch` retorna IDs ranqueados por `ts_rank`. `RagService.retrieve` faz busca vetorial + lexical e funde via Reciprocal Rank Fusion (k=60).
- [x] **Detecção de PDF escaneado** — `PdfExtractor` conta páginas com < 30 chars. Se ≥70% das páginas estão "vazias", lança `ScannedPdfException` com mensagem clara orientando a rodar `ocrmypdf` externamente. (OCR completo com Tesseract fica pra Fase 2.1 — adiciona ~50MB de dep nativa e foge do mínimo viável.)
- [x] **Cache Caffeine de query embedding** — `CachingEmbeddingModel` @Primary com TTL 1h, max 1000. Cacheia só chamadas single-input (queries do usuário); batch de indexação passa direto.
- [x] **Métrica de qualidade de retrieval** — Migration V9 + `RetrievalMetric` entity. Persiste por pergunta: `chunks_retrieved`, `avg_distance`, `top1_distance`, `no_results`, `used_lexical_fusion`. Tabela populada em todo `answer` e `answerStream`.
- [x] **Contexto adaptativo** — `RagService.cutByTokenBudget` corta a lista do reranker quando ultrapassa `acervo.rag.max-context-tokens` (default 6000). Sempre mantém ao menos `acervo.rag.min-chunks` (default 3). Estimativa simples ~4 chars/token.

### Critérios de aceitação

- Reranker reduz chunks irrelevantes em pelo menos 30% (medido manualmente com 20 perguntas de teste).
- Hybrid search melhora recall em perguntas com termos exatos (medido com 10 queries do tipo "o que diz a página X").
- OCR funciona em pelo menos 1 PDF escaneado do conjunto de teste.
- Tabela `retrieval_metrics` populando em cada resposta.

### Dependências

- **Fase 1 (testes)** — sem cobertura, mexer no RAG vira jogo de azar.

---

## Fase 3 — UX e produtividade do estudante ✅

**Objetivo:** tornar o uso diário mais fluido. Pode rodar **em paralelo à Fase 2** (não compartilham código).

### Itens

- [x] **Streaming SSE da resposta** — `RagService.answerStream` faz subscribe no `ChatModel.stream(Prompt)` e cada delta vira evento SSE. `ChatController.stream` expõe `text/event-stream` em `/chat/conversations/{id}/stream`. Frontend (`AcervoChat.send`) abre `EventSource` e popula a bolha do assistente token a token; ao final dispara reload pra trazer as citações.
- [x] **Atalhos de teclado** — `Ctrl+K` abre dropdown de matérias, `Ctrl+/` foca no composer, `Esc` fecha modais, `Enter` envia (Shift+Enter quebra linha), setas ↑↓ navegam o histórico, `F` alterna tela cheia, `?` abre modal com a lista. Botão `?` no header do chat também abre.
- [x] **Exportar conversa** — `GET /chat/conversations/{id}/export?format=md` retorna Markdown com pergunta + resposta + fontes (nome do documento, página, relevância, excerpt). Botão ⤓ no header do chat dispara o download. PDF fica pra v2.1.
- [x] **Indicador "última conversa visitada"** — `ChatController` salva o id da conversa aberta em `HttpSession` por matéria. Na sidebar do chat, ao cair no welcome, um ponto dourado pequeno (`.history-last-tag`) destaca discretamente a última conversa visitada.
- [x] **Highlight do trecho citado** — clicar numa fonte do painel abre `#citation-modal` mostrando: badge da extensão, nome do documento, página, relevância, a pergunta que originou a citação e o trecho completo do chunk destacado num bloco com borda dourada. Modal abre via `AcervoCitation.show()`; dados via `data-*` no botão renderizado pelo Thymeleaf.
- [x] **Histórico global de busca** — campo `<input type="search">` no header da sidebar do chat. `AcervoHistorySearch.filter()` filtra os `.history-item` por `data-search` (título + concat das mensagens). Quando nenhum casa, mostra `#history-empty-results`. Client-side por enquanto; FTS no Postgres fica pra evolução.
- [x] **Modo de tela cheia do thread** — `AcervoFullscreen.toggle()` (botão ⛶ no header ou tecla `F`) adiciona `body.chat-fullscreen` que esconde sidebar, histórico e painel via CSS; o thread ocupa o espaço total com `max-width: 920px`. Estado persiste em `localStorage`.

### Critérios de aceitação

- Streaming: primeiro token aparece em < 1.5s (com Qwen 7B no Mac).
- Atalhos: pelo menos 4 atalhos funcionais documentados num modal "?".
- Exportação MD funcional; PDF pode ficar pra v2.1.
- Highlight do chunk funciona pelo menos em PDF.

### Dependências

- Streaming SSE precisa do `ChatModel` do Spring AI suportar streaming (suporta).
- Highlight precisa de mudança no schema: `Chunk` ganha `startOffset` e `endOffset` (migration nova).

---

## Fase 4 — Multi-usuário ✅

**Objetivo:** sair de "demo pessoal" pra produto multi-tenant. Autenticação real, isolamento de dados, compartilhamento.

### Itens

- [x] **Spring Security + form login** — `SecurityConfig` (spring-security 6.3) substitui o `SessionGuardFilter`. `User` entity (`email` unique, `passwordHash` bcrypt, `firstName`, `lastName`, `contact`, `role`). Form login em `/landing` POST `/login` (parâmetros `email`/`password`); signup em `/signup` com auto-login. `AcervoUserDetailsService` carrega user pelo email; `AcervoUserDetails` adapta pro contrato do Spring Security. Reset de senha via aba "Segurança" (mudança em sessão; email reset fica pra v2). CSRF habilitado, com SSE `/chat/conversations/*/stream` excluído (GET only).
- [x] **Migração do `Profile` singleton pra `User`** — `Profile`, `ProfileService`, `ProfileRepository` deletados. `User` substitui em todos os lugares (templates ainda usam `${profile.fullName()}` via `GlobalModelAttributes` que injeta User como `profile`). `BootstrapDataInitializer` cria conta `root@acervo.local` (ADMIN) com senha aleatória logada uma única vez na primeira inicialização — sobrescrever via `ACERVO_BOOTSTRAP_ROOT_EMAIL`/`_PASSWORD`. `SchemaBootstrap` adopta matérias órfãs (sem `owner_id`) pro primeiro usuário cadastrado — bases legadas Fase-3 continuam acessíveis.
- [x] **Isolamento de dados** — `Subject.owner` (FK `User`, cascade delete) + unique constraint `(owner_id, name)` (usuários diferentes podem ter matérias com mesmo nome). `Conversation`/`Document` herdam via `subject.owner`. `SubjectRepository.findByOwner_*`, `ConversationRepository.findBySubject_IdAndSubject_Owner_Id*`, `DocumentRepository.findBySubject_IdAndSubject_Owner_Id*`. Services rejeitam acessos cross-user com exceção; controllers usam `CurrentUser.id()` em todas as ações. Teste E2E `isolationBetweenUsers` valida.
- [x] **Compartilhamento de matéria (read-only)** — `SubjectShare` entity com token aleatório de 48 hex chars. `POST /subjects/{id}/share` cria; `POST /shares/{id}/revoke` revoga (marca `revoked_at`); `GET /shared/{token}` mostra `templates/shared.html` com documentos + conversas em modo leitura, sem acesso ao chat. Token expirado/revogado cai em `shared-error.html`. Botão "↗ Compartilhar" no header de cada matéria da página `/import`.
- [x] **Perfis de usuário (Aluno/Professor/Admin)** — `User.Role` enum (`ALUNO`, `PROFESSOR`, `ADMIN`). Aluno/Professor são selecionáveis no signup; Admin só é semeado pelo `BootstrapDataInitializer` (não exposto na UI). Vira `ROLE_ALUNO`/`ROLE_PROFESSOR`/`ROLE_ADMIN` no `GrantedAuthority` — pronto pra `@PreAuthorize("hasRole('ADMIN')")` em endpoints futuros.
- [x] **Auditoria básica** — `AuditLog` entity (`user_id`, `action`, `payload`, `at`); `AuditService.record(...)` é não-bloqueante (falhas só viram WARN). `AuthEventListener` captura `LOGIN_SUCCESS`/`LOGIN_FAILED`; logout via `LogoutHandler` no `SecurityConfig`. Cobre também `SIGNUP`, `PASSWORD_CHANGED`/`_FAILED`, `SUBJECT_CREATED`/`_RENAMED`/`_DELETED`, `SUBJECT_SHARED`, `SHARE_REVOKED`, `ROOT_USER_CREATED`. Aba "Auditoria" no perfil mostra últimas 50.
- [x] **Página de gerenciamento de conta** — `/profile` ganhou 4 abas (`?tab=`): **Dados pessoais** (nome, sobrenome, contato; email read-only), **Segurança** (trocar senha — bcrypt verifica a atual, invalida sessões antigas via `SessionRegistry.expireNow()`), **Compartilhamentos** (lista todos os links com badge ativo/revogado e botão de revogar) e **Auditoria** (últimas 50 entradas).
- [x] **Reset do banco por completo** — `SchemaResetInitializer` (`EnvironmentPostProcessor`) lê `acervo.bootstrap.reset-on-start` (ou env `ACERVO_BOOTSTRAP_RESET=true`); se true, sobrescreve `spring.jpa.hibernate.ddl-auto` para `create` antes do Hibernate ler — dropa e recria todo o schema no boot. ⚠ destrutivo; desligar a flag após o startup pra não apagar tudo de novo no próximo boot.


### Critérios de aceitação

- Dois usuários diferentes não enxergam dados um do outro (testável via E2E).
- Link de compartilhamento funciona em janela anônima.
- Troca de senha invalida sessões antigas.
- Login persiste por 7 dias com "lembre-se de mim" (cookie + token).

### Dependências

- **Fase 1 (testes)** — refatoração de schema sem cobertura é receita pra regressão silenciosa.
- Migrations cuidadosas: V9 adiciona colunas nullable; V10 backfilla; V11 torna NOT NULL. Nunca uma migration única destrutiva.

### Risco

Esta fase é a maior em LOC. Considerar fazer em duas sub-fases (4a: auth + isolamento; 4b: compartilhamento + perfis).

---

## Fase 5 — Inteligência avançada ✅

**Objetivo:** o LLM deixa de só responder e começa a **gerar material de estudo**. Aqui o RAG vira ferramenta de aprendizado ativo.

### Itens

- [x] **Sumarização de documento** — botão "✎ Resumir" no card de cada documento `INDEXED` em `/import`. Página `/import/documents/{id}/summary` mostra 3 cards (1 parágrafo, 1 página, mapa mental); cada um chama `POST .../summary/{level}` (JSON) sob demanda. `SummaryService` injeta os chunks no LLM com prompt por nível; persiste em `DocumentSummary` com unique `(document_id, level)` (regenerar substitui).
- [x] **Flashcards** — `POST /subjects/{id}/flashcards/generate?count=20` chama o LLM e parseia o formato `P:/R:/F:` separado por `---`. `Flashcard` entity guarda pergunta + resposta + fonte + campos SM-2 (`easeFactor`, `intervalDays`, `repetitions`, `dueAt`). Tela `/subjects/{id}/flashcards` faz revisão estilo Anki: mostra pergunta → revela resposta → 4 botões de qualidade (Errei/Difícil/Acertei/Fácil); `FlashcardService.review(id, quality)` aplica SM-2 simplificado e agenda `dueAt`.
- [x] **Quiz interativo** — `POST /subjects/{id}/quiz/generate?count=10&difficulty=EASY|MEDIUM|HARD`. `QuizService` extrai do LLM o formato `Q: / A) B) C) D) / CORRETA: / EXPLICACAO: / FONTE:` separado por `---`. Tela `/subjects/{id}/quiz` lista as questões; clicar em uma opção dispara `POST /quiz/{id}/answer?selected=N` e marca verde/vermelho com explicação inline citando o documento.
- [x] **Modo "estudo dirigido"** — `StudySession` + `StudyTurn` entities; `POST /subjects/{id}/study/start` abre sessão e o tutor faz a primeira pergunta; cada `POST /study/{id}/respond` registra a resposta do aluno e o tutor avalia + faz a próxima pergunta. Prompt usa formato `AVALIACAO: / PROXIMA:` no histórico completo. `POST /study/{id}/finish` encerra.
- [x] **Detecção de gaps de conhecimento** — `GapAnalysisService.suggestForUser(userId, subjectId, limit)` tokeniza as mensagens USER das conversas (stopwords + min len 5), pondera por frequência e devolve `TopicSuggestion(term, count)`. `/inicio` ganhou bloco "PRA REVISAR" com, por matéria: chips dos tópicos sugeridos, contagem de flashcards `due`, e atalhos Flashcards/Quiz/Estudo.
- [x] **Geração de mapa de tópicos** — `TopicMapService.getOrGenerate(subjectId)` faz amostragem dos chunks e pede ao LLM um mapa indentado markdown (3 níveis máx); resultado vai pra um cache Caffeine (TTL 1h, max 200). Página `/subjects/{id}/topics` renderiza num `<pre>`; botão "↻ Regenerar" invalida o cache.
- [x] **Voz** — `AcervoVoice` no `acervo.js`: mic 🎙 no composer do chat usa Web Speech API (`SpeechRecognition` em `pt-BR`) pra ditar; `AcervoVoice.speak(text)` usa `SpeechSynthesis` (TTS nativo do browser) pra ler respostas. Mic piscando enquanto grava. Sem dep de Piper — TTS do navegador resolve.

### Critérios de aceitação

- Flashcards gerados têm pergunta e resposta coerentes em pelo menos 80% dos casos (validação manual com 30 cards).
- Quiz não inventa alternativas absurdas; todas plausíveis pelo contexto.
- Modo estudo dirigido mantém contexto por pelo menos 10 turnos sem perder o fio.
- Voz funciona em Chrome desktop.

### Dependências

- **Fase 2** — sem reranker, a qualidade dos flashcards/quiz cai (gera com base em chunks irrelevantes).
- **Fase 3** — streaming melhora muito a UX do modo "estudo dirigido".

---

## Backlog (sem fase definida)

Ideias que apareceram durante o planejamento mas não couberam em nenhuma fase ainda. Promovem pra fase quando virar prioridade.

- **Pipeline de indexação em fila externa** (RabbitMQ/SQS) — só faz sentido com tráfego real.
- **PWA / mobile-first** — depende de demanda; hoje o app é desktop-only.
- **Múltiplos provedores LLM** — abstração pra trocar entre LM Studio, Ollama, OpenAI por matéria/usuário.
- **Versionamento de documentos** — re-upload mantém histórico em vez de sobrescrever.
- **Tags em documentos** — orthogonal a "matéria". Permite cross-cutting (ex: "aula introdutória" em várias matérias).
- **Modo offline-first** — cache de respostas anteriores; permite revisão sem conexão com LM Studio.
- **Integrações** — exportar pro Notion, Anki, Obsidian.
- **Métricas de uso pessoal** — quanto tempo estudou hoje, qual matéria mais ativa, etc.
- **Tema claro** — só tema dark hoje.

---

## Como usar este documento

1. **Não execute em paralelo demais.** Fase 1 antes de qualquer outra. Fases 2 e 3 podem ir juntas. Fase 4 isolada (refatoração grande). Fase 5 por último.
2. **Marque os checkboxes conforme avança.** O checkbox vazio é um TODO real.
3. **Mantenha os critérios de aceitação honestos.** Se passou de fase sem cumpri-los, está acumulando dívida — anota numa seção "Pendências" aqui no `Melhorias.md` ou abre uma issue.
4. **O backlog cresce; o roadmap não precisa.** Ideias novas vão pro Backlog; só sobem pra fase quando você decidir priorizar.
5. **Revise a cada 2-3 sprints.** Realidade muda; reordenar é saudável.
