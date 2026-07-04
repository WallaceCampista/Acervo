# Acervo — Estudo Aumentado (RAG)

Aplicação **Spring Boot + Thymeleaf** que reimplementa o protótipo `Acervo` (mockup em `~/Downloads/RAG_UNI`) como um assistente de estudo com RAG por matéria. Os modelos de chat e embedding rodam **100% local** via **LM Studio** — nada é enviado pra fora da sua máquina.

> Veja [`Melhorias.md`](./Melhorias.md) para o roadmap dos próximos passos.

## Stack

- Java 21 · Spring Boot 3.3 · Thymeleaf 3
- Spring AI 1.0.x (starter OpenAI apontando pra LM Studio local)
- PostgreSQL 16 + **pgvector**
- LM Studio — chat (`gemma-2-9b-it`, só texto) + embedding (`nomic-embed-text-v1.5`, 768 dims). Para anexo de imagem no chat, use um modelo multimodal como `gemma-3-4b-it`.
- Apache PDFBox / POI / commonmark — extração de PDF, DOCX, PPTX, MD
- Maven · Lombok
- JUnit 5 + Spring Boot Test + Testcontainers 1.21 (Postgres+pgvector)
- Schema gerenciado por Hibernate (`ddl-auto: update`) + `SchemaBootstrap` para o que JPA não cria (extensão `vector`, `chunk.content_tsv`, índice GIN)

## Como rodar (modo desenvolvimento)

### 1. LM Studio

1. Baixe e instale o [LM Studio](https://lmstudio.ai/).
2. Aba **Discover** → baixe (filtre por **GGUF**, não MLX):
   - Chat: `lmstudio-community/Qwen2.5-7B-Instruct-GGUF` (Q4_K_M, ~4.5 GB) — ou outro de sua preferência.
   - Embedding: `nomic-ai/nomic-embed-text-v1.5-GGUF` (Q8_0, ~146 MB).
3. Aba **Developer** (ou **Local Server**): carregue os **dois modelos** simultaneamente. Para o chat, suba o **context length** para pelo menos **16k** (default é 4k — apertado pro RAG).
4. Inicie o servidor — confirme que aparece `http://127.0.0.1:1234`.

### 2. Postgres

```bash
docker compose up -d
```

### 3. Aplicação

```bash
mvn spring-boot:run
```

Abra `http://localhost:8080`. A landing aparece — clique **Entrar** pra acessar o sistema.

> Em troca de modelo de chat no LM Studio, ajuste `ACERVO_CHAT_MODEL` (ou edite `application-dev.yml`).

## Variáveis de ambiente

| Variável | Default | Descrição |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` | Perfis: `dev` (Postgres local + LM Studio), `prod` (variáveis externas) |
| `ACERVO_AI_BASE_URL` | `http://localhost:1234` | URL base do servidor de inferência (Spring AI adiciona `/v1/...`) |
| `ACERVO_AI_API_KEY` | `lm-studio` | Chave da API. LM Studio ignora, mas o starter exige uma string |
| `ACERVO_CHAT_MODEL` | `gemma-2-9b-it` | Identificador do modelo de chat no LM Studio (texto). Para visão/imagem, use um multimodal como `gemma-3-4b-it` |
| `ACERVO_EMBEDDING_MODEL` | `text-embedding-nomic-embed-text-v1.5@q8_0` | Identificador do modelo de embedding |
| `ACERVO_EMBEDDING_DIMENSIONS` | `768` | Tamanho do vetor — precisa bater com o modelo |
| `STORAGE_DIR` | `./data/uploads` | Diretório onde os arquivos enviados ficam armazenados |
| `SPRING_DATASOURCE_URL` | (obrig. em prod) | JDBC do Postgres em produção |
| `SPRING_DATASOURCE_USERNAME` | (obrig. em prod) | Usuário do banco |
| `SPRING_DATASOURCE_PASSWORD` | (obrig. em prod) | Senha do banco |

## Perfis

- **`dev`** (default): Postgres em `localhost:5432`, LM Studio em `localhost:1234`, Thymeleaf sem cache, logs detalhados.
- **`prod`**: lê banco e provedor de IA de variáveis de ambiente, Thymeleaf com cache, sem logging debug.

## Estrutura

```
src/main/java/com/acervo/
├── AcervoApplication.java
├── config/       # WebConfig, SessionGuardFilter (proteção de rotas)
├── controller/   # ChatController, ImportController, SubjectController,
│                 # ProfileController, AuthController, DashboardController,
│                 # HomeController, GlobalModelAttributes
├── domain/       # Subject, Document, Chunk, Conversation, Message, Citation, Profile
├── ingest/       # PdfExtractor, DocxExtractor, PptxExtractor, Chunker
├── rag/          # EmbeddingPipeline, RagService,
│                 # LocalEmbeddingModel (chama LM Studio via /v1/embeddings),
│                 # AiFailureTranslator
├── repository/   # Spring Data JPA
└── service/      # SubjectService, DocumentService, ConversationService, ProfileService

src/main/resources/
├── application.yml + application-dev.yml + application-prod.yml
├── messages.properties + messages_pt_BR.properties
├── templates/    # layout, landing, inicio, chat, chat-empty, import, profile,
│                 # error/4xx, error/5xx, fragments/ (sidebar, user-menu, modal)
└── static/       # css/acervo.css, js/acervo.js
```

## Fluxos principais

1. **Login** — `/landing` → **Entrar** → marca flag na `HttpSession` → `/inicio`. Não há autenticação real; é só uma porta de entrada visual.
2. **Início** — `/inicio` mostra saudação personalizada (via `Profile`), contadores (matérias, documentos, conversas) e cards de atalho.
3. **Importação** — `/import` → cria matéria → arrasta arquivos → pipeline assíncrono extrai, chunka, embeda via `LocalEmbeddingModel` (LM Studio) e indexa no pgvector.
4. **Chat** — `/chat?subject=ID` → sempre abre na **tela inicial** (nunca na última conversa). `/chat?subject=ID&conv=CONV` carrega a conversa específica. `RagService.answer` faz `similaritySearch` filtrando por `subjectId`, monta prompt, chama o LLM e persiste resposta + citações + tempo de geração.
5. **Painel de fontes** — sempre exibido à direita. Agrupa as fontes por pergunta com a barra de relevância, e mostra o tempo de resposta abaixo da pergunta.
6. **Perfil** — `/profile` permite editar nome, sobrenome e contato. O avatar (iniciais) aparece no header e na sidebar.
7. **Logout** — invalida a `HttpSession` → volta pra `/landing`.

## RAG

- `RagService` usa o prompt-system padrão "responde APENAS pelo contexto", e quando não há contexto suficiente devolve **"Não encontrei isso na nossa base de dados."**
- `top-k = 6` (configurável em `acervo.rag.top-k`), chunks de 500 tokens com overlap de 80 (`acervo.rag.chunk-size` / `acervo.rag.chunk-overlap`).
- O `LocalEmbeddingModel` fala direto com `/v1/embeddings` do LM Studio — evita o bug do Spring AI 1.0.0-M3 que exige `usage != null` no response (LM Studio não preenche esse campo). Um `CachingEmbeddingModel` `@Primary` (Caffeine, TTL 1h, max 1000) cacheia embeddings de queries — perguntas repetidas economizam round-trip.

### Pipeline de retrieval

A busca por contexto combina três técnicas pra entregar chunks melhores que o `similaritySearch` puro:

1. **Hybrid search** — busca vetorial (pgvector cosine) **em paralelo** à busca lexical (Postgres FTS `to_tsvector('portuguese', ...)` + índice GIN) e funde via **Reciprocal Rank Fusion** (k=60). Resolve queries com termos exatos onde a vetorial se perdia em paráfrase.
2. **MMR reranker** — `MmrReranker` re-ordena o pool fundido aplicando Maximum Marginal Relevance, balanceando relevância × diversidade (`λ=0.7` default, configurável via `acervo.rag.mmr-lambda`). Reduz chunks redundantes.
3. **Contexto adaptativo** — em vez de top-K fixo, corta a lista quando ultrapassa `acervo.rag.max-context-tokens` (default 6000), mantendo no mínimo `acervo.rag.min-chunks` (default 3). Perguntas simples economizam tokens; complexas pegam até o teto.

Cada retrieval salva um snapshot na tabela `retrieval_metric` (`chunks_retrieved`, `avg_distance`, `top1_distance`, `no_results`, `used_lexical_fusion`) — útil pra detectar regressões via SQL ad-hoc.

### Detecção de PDF escaneado

O `PdfExtractor` conta páginas com menos de 30 caracteres extraíveis. Se ≥70% das páginas estão "vazias", o pipeline marca o documento como `FAILED` com a mensagem **"PDF parece ser escaneado (...). Rode OCR (ex: ocrmypdf) no arquivo e reenvie."** — em vez de indexar um PDF inútil sem texto. OCR automatizado fica fora da v1 por causa do tamanho da dep nativa.

## Sessão e proteção de rotas

- `SessionGuardFilter` (`OncePerRequestFilter`) protege todas as rotas exceto: `/landing`, `/login`, `/logout`, `/favicon.ico`, `/css/`, `/js/`, `/images/`, `/webjars/`, `/actuator/`.
- Sem `acervo.logged=true` na sessão → redirect pra `/landing`.
- **Não é autenticação real.** Não há senha, hash, JWT ou sequer username — só uma flag em sessão. Pra evoluir pra multi-usuário ver "Próximos passos".

## Validação de upload

- Tamanho máximo: 50 MB (validação no controller + `spring.servlet.multipart.max-file-size`).
- Extensões aceitas: `PDF`, `DOCX`, `PPTX`, `TXT`, `MD`.
- Nome do arquivo sanitizado contra XSS (caracteres de controle, `<>"'\``, barras, pipe e curingas removidos) e path traversal (`Path::getFileName` + verificação `Path::startsWith`).
- Armazenamento físico em `data/uploads/{subjectId}/{uuid}.{ext}` — o `originalName` é preservado somente no banco.

## UX do chat

- **Auto-scroll inteligente:** a cada nova interação, o thread rola pra última pergunta ficar no topo da view (padrão ChatGPT/Claude — pergunta visível + resposta começando logo abaixo). Idem pro painel de fontes.
- **Streaming SSE:** a resposta aparece token a token, em tempo real. `GET /chat/conversations/{id}/stream` retorna `text/event-stream`; `RagService.answerStream` (@Async) faz subscribe no `ChatModel.stream(Prompt)` e cada delta vira evento `message`. Ao final, evento `done` dispara um reload pra trazer as citações renderizadas no painel.
- **Otimismo no envio:** a mensagem do usuário aparece instantaneamente; a bolha do assistente é criada vazia e preenchida conforme os tokens chegam — não precisa esperar a resposta inteira pra dar feedback visual.
- **Ellipsis no painel:** nomes longos de documentos e textos de pergunta são truncados via CSS pra não quebrar o layout lateral.
- **Tempo de resposta:** abaixo da pergunta no painel, formatado em `s` ou `ms` (ex: `2.3s`, `420ms`).

## Testes

Suíte com **45 testes** cobrindo persistência, RAG (incluindo reranker MMR e cache Caffeine), ingestão (PDFs escaneados detectados), e fluxo end-to-end de chat (com streaming SSE). Usa **Testcontainers** (Postgres com pgvector em container efêmero) + **MockMvc** + **Mockito** (para `VectorStore` e `ChatModel`).

```bash
mvn test       # roda os testes
mvn verify     # roda testes + check de cobertura JaCoCo (mín. 60% em rag/ e ingest/)
```

**Pré-requisitos:**
- **Docker** rodando (Docker Desktop ou equivalente). Os testes sobem um container `pgvector/pgvector:pg16` automaticamente.
- O `pom.xml` já contém toda a configuração necessária — não é preciso setar variáveis de ambiente:
  - `<api.version>1.43</api.version>` — Docker Desktop ≥29 exige API ≥1.40; o default do `docker-java` é 1.32 e o daemon recusa.
  - `<net.bytebuddy.experimental>true</net.bytebuddy.experimental>` — Mockito/Byte Buddy ainda não declaram suporte oficial a Java 25.
  - `<TESTCONTAINERS_RYUK_DISABLED>true</TESTCONTAINERS_RYUK_DISABLED>` — Ryuk tenta montar o socket como volume, o que falha no Docker Desktop macOS.
  - Profiles `mac-docker-desktop` e `linux-docker` apontam `DOCKER_HOST` pro socket correto em cada plataforma.

**O que cobre:**

| Suite | Foco |
|---|---|
| `AcervoApplicationTests` | Contexto Spring sobe com `test` profile, Hibernate cria o schema (`ddl-auto: create-drop`) e o `SchemaBootstrap` aplica os DDLs complementares |
| `SubjectRepositoryTest` | CRUD, `existsByNameIgnoreCase`, unique constraint |
| `DocumentRepositoryTest` | Ordenação por data, count por subject/status, cascade delete |
| `ChunkRepositoryTest` | findByDocument, count por subject, delete seletivo, cascade |
| `ConversationRepositoryTest` | findBySubject ordenado, cascade |
| `MessageAndProfileRepositoryTest` | Message com citations + `responseTimeMs`, cascade, Profile singleton |
| `RagServiceTest` | top-K + filtro por subjectId no SearchRequest, citation linka ao chunk certo, `responseTimeMs`, fallback sem retrieval, erro do LLM amigável, chunkId inválido tolerado, `retrieval_metric` persistida |
| `CachingEmbeddingModelTest` | hit/miss em single-input, queries diferentes em entradas separadas, batch ignora cache, `embed(Document)` ignora cache, `dimensions()` delega |
| `MmrRerankerTest` | passa direto quando candidates ≤ topN, prefere relevante quando candidates dispares, diversifica entre quase-idênticos com λ baixo, fallback pra ordem original quando embed falha |
| `EmbeddingPipelineTest` | `PROCESSING → INDEXED` no caminho feliz com chunks e `vectorStore.add`, `PROCESSING → FAILED` com `failureReason` traduzida, `CANCELLED` não sobrescrito, doc inexistente tolerado |
| `PdfExtractorTest` | extração normal de PDF com texto, detecção de PDF com ≥70% páginas vazias (ScannedPdfException), PDF totalmente vazio |
| `ChatFlowIntegrationTest` | **E2E:** pergunta síncrona → resposta com citation; `SessionGuardFilter` redireciona pra `/landing`; erro do LLM vira mensagem amigável; **streaming SSE** acumula tokens e persiste no fim; erro durante streaming vira mensagem amigável |

O container do Postgres é estático (`withReuse(true)`) — sobe uma vez por execução de `mvn test`, todos os testes compartilham. Cada teste limpa os próprios dados via `deleteAll()` em `@BeforeEach`/`@AfterEach`.

## Observabilidade

Métricas no formato Prometheus expostas em `/actuator/prometheus`, com histogramas configurados para latência p50/p95/p99 nos seguintes timers:

| Métrica | O que mede |
|---|---|
| `acervo_rag_answer_seconds` | Tempo da resposta síncrona (`RagService.answer`) |
| `acervo_rag_answer_stream_seconds` | Tempo total do streaming SSE, do handshake ao `done` |
| `acervo_embedding_process_seconds` | Tempo de extrair + chunkar + embeddar + indexar um documento |

Endpoints do actuator habilitados: `health`, `info`, `metrics`, `prometheus`.

**Health check do LM Studio:** `GET /actuator/health/lmStudio` faz `GET /v1/models` com timeout de 2s e reporta `UP` (com `latencyMs`) ou `DOWN` (com `error`). Útil pra healthcheck do Docker em produção.

```bash
curl http://localhost:8080/actuator/health/lmStudio
# {"status":"UP","details":{"baseUrl":"http://localhost:1234","latencyMs":12}}
```

## Acessibilidade

- *Skip link* para conteúdo principal em todas as páginas.
- `role="navigation"`, `role="main"`, `role="banner"`, `role="log"` (com `aria-live="polite"` no histórico do chat).
- `aria-label`, `aria-pressed`, `aria-current` nos controles segmentados, navegação e ações destrutivas.
- Foco visível (`outline: 2px solid var(--gold)`) em todos os controles interativos.
- Composer com `<label class="visually-hidden">` para leitores de tela.
- Toda iconografia decorativa marcada com `aria-hidden="true"`.

## Internacionalização

- `messages_pt_BR.properties` é o catálogo principal; `messages.properties` é o fallback em inglês.
- Locale fixado em `pt-BR` via `FixedLocaleResolver` (sem troca pelo browser).
- Bundle carregado por `ReloadableResourceBundleMessageSource` com encoding UTF-8.

## Páginas de erro

- `templates/error/4xx.html` cobre todos os status 4xx (404, 400, etc.).
- `templates/error/5xx.html` cobre todos os status 5xx.
- Ambas usam o tema dark/dourado, oferecem botão para voltar ao chat e exibem código + detalhe quando disponível.

## Endpoints úteis

- `GET /` → redireciona para `/inicio`
- `GET /landing` · `POST /login` · `POST /logout`
- `GET /inicio` (dashboard pós-login)
- `GET /chat?subject=<id>` · `GET /chat?subject=<id>&conv=<id>`
- `POST /chat/conversations` (cria conversa)
- `POST /chat/conversations/{id}/messages` (envia pergunta)
- `GET /import?subject=<id>&view=list|gallery`
- `POST /import/{subjectId}/upload`
- `POST /subjects` (cria matéria) · `POST /subjects/{id}/delete` · `POST /subjects/{id}/rename`
- `GET /profile` · `POST /profile`
- `GET /actuator/health`

## Deploy (produção)

A aplicação tem um `Dockerfile` multi-stage (Maven → JRE 21 slim) e um `docker-compose.prod.yml` que sobe Postgres + app com volumes persistentes.

> Em produção, o LM Studio (desktop app) **não roda** dentro do container — você precisa de outro provedor de inferência OpenAI-compat acessível pela rede (ex: Ollama, vLLM, llama.cpp server) e apontar `ACERVO_AI_BASE_URL` pra ele. Se mantiver o LM Studio rodando no host, use `http://host.docker.internal:1234` no `ACERVO_AI_BASE_URL` pro container alcançar.

```bash
# 1. Configure variáveis (copie .env.example → .env e edite)
cp .env.example .env
# edite POSTGRES_PASSWORD, ACERVO_AI_BASE_URL e ACERVO_AI_API_KEY no mínimo

# 2. Build + sobe
docker compose -f docker-compose.prod.yml up -d --build

# 3. Acompanhe logs / healthcheck
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f app

# 4. Acesse
open http://localhost:${APP_PORT:-8080}
```

Detalhes da imagem:
- Roda como usuário não-root (`acervo`), pasta de trabalho `/opt/acervo`.
- `EXPOSE 8080`, healthcheck em `/actuator/health`.
- Uploads persistidos no volume nomeado `uploads` → `/opt/acervo/data/uploads`.
- Dados Postgres persistidos no volume nomeado `pg-data`.
- `JAVA_OPTS` padrão `-XX:MaxRAMPercentage=75.0` (ajustável via env).

## CI (GitHub Actions)

`.github/workflows/ci.yml`:
- Job `build`: setup JDK 21 (Temurin) com cache Maven, roda `mvn -B -ntp verify`, anexa o JAR como artefato.
- Job `docker`: build da imagem via `docker/build-push-action` com cache GHA. Em pushes para `main`, publica em `ghcr.io/<owner>/acervo:latest` e `:sha-<short>` usando o `GITHUB_TOKEN` padrão (basta habilitar Packages no repo).

Para pular o push, basta abrir o PR — a imagem é apenas construída como sanity check.

## Próximos passos (pós v1)

Ver [`Melhorias.md`](./Melhorias.md) — roadmap em 5 fases cobrindo testes/observabilidade, RAG mais inteligente (reranker, hybrid search, OCR), produtividade do estudante (atalhos, exportar conversa, highlight de citações), multi-usuário (auth real, compartilhamento) e features inteligentes (flashcards, quiz, voz).
