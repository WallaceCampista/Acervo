// Helper de CSRF — lê <meta name="_csrf"> e <meta name="_csrf_header">
// renderizados pelo Thymeleaf-spring-security em todas as páginas
// autenticadas. Devolve um objeto pra spread em headers de fetch.
window.AcervoCsrf = {
  headers() {
    const tok = document.querySelector('meta[name="_csrf"]');
    const hdr = document.querySelector('meta[name="_csrf_header"]');
    if (!tok || !hdr) return {};
    return { [hdr.content]: tok.content };
  }
};

// Overlay de carregamento pra ações que disparam geração no LLM (gerar
// flashcards/quiz, montar mapa de tópicos, iniciar/responder estudo dirigido).
// Essas ações são POST/GET síncronos que só respondem quando o modelo termina
// — sem feedback, a tela fica estática e o usuário não sabe que precisa
// esperar. Mostra spinner + barra indeterminada + tempo decorrido. O overlay
// some sozinho quando a navegação/redirect carrega a próxima página.
window.AcervoLoading = {
  timer: null,
  startedAt: 0,
  show(message) {
    let el = document.getElementById('acervo-loading');
    if (!el) {
      el = document.createElement('div');
      el.id = 'acervo-loading';
      el.className = 'loading-overlay';
      el.setAttribute('role', 'status');
      el.setAttribute('aria-live', 'polite');
      el.innerHTML =
          '<div class="loading-card">' +
          '<div class="loading-spinner" aria-hidden="true"></div>' +
          '<div class="loading-text"></div>' +
          '<div class="loading-bar" aria-hidden="true"><div></div></div>' +
          '<div class="loading-elapsed" aria-hidden="true">0s</div>' +
          '</div>';
      document.body.appendChild(el);
    }
    el.querySelector('.loading-text').textContent = message || 'Processando…';
    el.classList.add('show');
    document.body.classList.add('loading-locked');
    // Contador de tempo decorrido — dá sinal de vida quando a geração demora.
    this.startedAt = Date.now();
    const elapsed = el.querySelector('.loading-elapsed');
    elapsed.textContent = '0s';
    clearInterval(this.timer);
    this.timer = setInterval(() => {
      const s = Math.round((Date.now() - this.startedAt) / 1000);
      elapsed.textContent = s < 60 ? s + 's'
          : Math.floor(s / 60) + 'm ' + (s % 60) + 's';
    }, 1000);
  },
  hide() {
    const el = document.getElementById('acervo-loading');
    if (el) el.classList.remove('show');
    document.body.classList.remove('loading-locked');
    clearInterval(this.timer);
  }
};

// Dispara o overlay em qualquer form/link marcado com data-loading="mensagem".
// No submit usa fase de captura pra rodar antes de a navegação começar.
document.addEventListener('submit', e => {
  const form = e.target.closest('form[data-loading]');
  if (form) AcervoLoading.show(form.getAttribute('data-loading'));
}, true);
document.addEventListener('click', e => {
  const link = e.target.closest('a[data-loading]');
  if (link && link.href && !e.defaultPrevented
      && !e.metaKey && !e.ctrlKey && !e.shiftKey && e.button === 0) {
    AcervoLoading.show(link.getAttribute('data-loading'));
  }
});
// Voltar pelo histórico (bfcache) pode restaurar a página com o overlay
// aberto — esconde nesse caso.
window.addEventListener('pageshow', () => AcervoLoading.hide());

// Mantém thread e painel sempre mostrando o conteúdo mais recente: o fim da
// última resposta na thread e a última fonte no painel de referências. Assim
// o usuário vê a resposta assim que ela chega, sem precisar rolar pra baixo.
window.AcervoChatScroll = {
  // Limiar pra considerar o usuário "preso no fim" (ou seja, deve seguir o
  // streaming). Se ele rolou pra cima além disso, paramos de auto-rolar pra
  // não atrapalhar a leitura.
  STICK_THRESHOLD: 220,

  scrollToBottom(container) {
    if (container) container.scrollTop = container.scrollHeight;
  },
  // Posiciona thread e painel no fim — última resposta e última fonte.
  toLatest() {
    this.scrollToBottom(document.querySelector('.thread-scroll'));
    this.scrollToBottom(document.querySelector('.panel'));
  },
  // Durante o streaming: cola o final da thread no rodapé visível pra o
  // usuário ver o texto aparecendo. Se ele rolou pra cima propositalmente
  // (além de STICK_THRESHOLD do fim), respeita e não força.
  followStream() {
    const thread = document.querySelector('.thread-scroll');
    if (thread) {
      const dist = thread.scrollHeight - thread.scrollTop - thread.clientHeight;
      if (dist <= this.STICK_THRESHOLD) thread.scrollTop = thread.scrollHeight;
    }
    const panel = document.querySelector('.panel');
    if (panel) {
      const dist = panel.scrollHeight - panel.scrollTop - panel.clientHeight;
      if (dist <= this.STICK_THRESHOLD) panel.scrollTop = panel.scrollHeight;
    }
  }
};

// Composer com streaming via SSE: ao enviar, insere a mensagem do usuário e
// uma bolha vazia do assistente, abre EventSource e popula a bolha token a
// token. Ao terminar (evento "done"), recarrega a URL pra trazer o painel
// de citações renderizado pelo Thymeleaf.
window.AcervoChat = {
  send(e, form) {
    const textarea = form.querySelector('textarea[name="question"]');
    if (!textarea) return true;
    const question = textarea.value.trim();

    // Imagem anexada → precisa de visão do modelo, que só roda pelo POST
    // multipart tradicional (o canal SSE não carrega upload binário). Deixa
    // o submit nativo acontecer; a página recarrega com a resposta.
    const fileInput = form.querySelector('input[type="file"][name="image"]');
    const hasImage = fileInput && fileInput.files && fileInput.files.length > 0;
    if (hasImage) return true;

    if (!question) return false;

    e.preventDefault();

    // /chat/conversations/<uuid>/messages → extrai o id
    const match = form.action.match(/conversations\/([0-9a-f-]+)\/messages/i);
    if (!match) return true; // fallback pro POST tradicional
    const conversationId = match[1];
    const subjectIdInput = form.querySelector('input[name="subjectId"]');
    const subjectId = subjectIdInput ? subjectIdInput.value : '';

    const thread = document.querySelector('.thread-scroll');
    if (!thread) { form.submit(); return false; }

    const empty = thread.querySelector('.empty-thread');
    if (empty) empty.style.display = 'none';

    thread.appendChild(this.buildUserMessage(question));
    const assistantEl = this.buildAssistantStream();
    thread.appendChild(assistantEl);
    // Pula direto pro rodapé pra a auto-rolagem por chunk já considerar o
    // usuário "preso no fim" e seguir o streaming.
    thread.scrollTop = thread.scrollHeight;

    const sendBtn = form.querySelector('.send');
    if (sendBtn) sendBtn.disabled = true;
    textarea.disabled = true;
    textarea.value = '';

    const url = '/chat/conversations/' + conversationId
        + '/stream?question=' + encodeURIComponent(question);
    const es = new EventSource(url);
    const contentEl = assistantEl.querySelector('.content');
    const typingDots = assistantEl.querySelector('.typing-dots');

    AcervoTts.resetStream();

    let finalized = false;
    const finalize = (redirect) => {
      if (finalized) return;
      finalized = true;
      try { es.close(); } catch (_) {}
      if (typingDots) typingDots.remove();
      AcervoTts.streamEnd();
      // Recarrega pra trazer citações no painel — o conteúdo já está
      // renderizado, então a "piscada" é só na lateral. Antes de recarregar,
      // espera a fala terminar; senão o navigate corta a voz.
      if (redirect && subjectId) {
        AcervoTts.whenIdle(() => {
          location.replace('/chat?subject=' + encodeURIComponent(subjectId)
              + '&conv=' + conversationId);
        });
      }
    };

    es.addEventListener('message', ev => {
      if (typingDots) typingDots.remove();
      // Decodifica URL-encoding: o servidor encoda pra preservar espaços
      // líderes (BPE) que o parser SSE removeria.
      let token = '';
      try { token = decodeURIComponent(ev.data || ''); }
      catch (_) { token = ev.data || ''; }
      contentEl.textContent += token;
      AcervoTts.streamAppend(token);
      AcervoChatScroll.followStream();
    });
    es.addEventListener('done', () => finalize(true));
    es.addEventListener('error', ev => {
      // SSE dispatcha 'error' tanto pra erro real quanto pra desconexão.
      // Se já recebemos algum token, considera concluído; senão, recarrega
      // mesmo (msg de erro foi persistida pelo servidor).
      finalize(true);
    });

    return false;
  },

  buildUserMessage(text) {
    const article = document.createElement('article');
    article.className = 'msg user';
    article.setAttribute('aria-label', 'Sua mensagem');
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    const content = document.createElement('div');
    content.className = 'content';
    content.textContent = text;
    bubble.appendChild(content);
    article.appendChild(bubble);
    return article;
  },

  // Mostra um thumbnail da imagem escolhida no composer e remove o atributo
  // `required` lógico (texto vira opcional quando há imagem).
  previewImage(input) {
    const form = input.closest('.composer');
    if (!form) return;
    const file = input.files && input.files[0];
    const preview = form.querySelector('.composer-image-preview');
    const img = preview && preview.querySelector('img');
    if (!file || !preview || !img) return;
    if (!file.type.startsWith('image/')) {
      alert('Selecione um arquivo de imagem.');
      input.value = '';
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      alert('Imagem muito grande (máx. 10MB).');
      input.value = '';
      return;
    }
    img.src = URL.createObjectURL(file);
    preview.hidden = false;
  },

  // Remove a imagem anexada e limpa o preview.
  clearImage(btn) {
    const form = btn.closest('.composer');
    if (!form) return;
    const input = form.querySelector('input[type="file"][name="image"]');
    const preview = form.querySelector('.composer-image-preview');
    const img = preview && preview.querySelector('img');
    if (input) input.value = '';
    if (img && img.src) { try { URL.revokeObjectURL(img.src); } catch (_) {} img.removeAttribute('src'); }
    if (preview) preview.hidden = true;
  },

  // Preenche o composer com o texto de uma sugestão e foca pra o usuário
  // editar antes de enviar. Não submete automaticamente — o submit fica como
  // ação explícita.
  fillPrompt(btn) {
    const text = (btn && btn.getAttribute('data-prompt')) || '';
    const ta = document.querySelector('.composer textarea');
    if (!ta || !text) return;
    ta.value = text;
    ta.dispatchEvent(new Event('input'));
    ta.focus();
    // Coloca o cursor no fim
    try { ta.setSelectionRange(text.length, text.length); } catch (_) {}
  },

  buildAssistantStream() {
    const article = document.createElement('article');
    article.className = 'msg assistant streaming';
    article.setAttribute('aria-label', 'Resposta do Acervo');
    article.innerHTML =
        '<div class="assistant-side">' +
        '<div class="avatar-a" aria-hidden="true">A</div>' +
        '<button type="button" class="tts-toggle" title="Silenciar leitura por voz"' +
        ' aria-label="Silenciar leitura por voz" aria-pressed="false"' +
        ' onclick="AcervoTts.toggle()">' +
        '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor"' +
        ' stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
        '<polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/>' +
        '<path class="tts-wave" d="M15.54 8.46a5 5 0 0 1 0 7.07"/>' +
        '<path class="tts-wave" d="M19.07 4.93a10 10 0 0 1 0 14.14"/>' +
        '<line class="tts-mute-line" x1="3" y1="3" x2="21" y2="21"/>' +
        '</svg></button>' +
        '</div>' +
        '<div class="bubble">' +
        '<div class="content"></div>' +
        '<div class="typing-dots" role="status" aria-live="polite">' +
        '<span></span><span></span><span></span>' +
        '</div>' +
        '</div>';
    return article;
  }
};

// Auto-resize do composer + Enter para enviar (Shift+Enter quebra linha)
document.querySelectorAll('.composer textarea').forEach(ta => {
  ta.addEventListener('input', () => {
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 140) + 'px';
  });
  ta.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      const form = ta.closest('form');
      const fileInput = form.querySelector('input[type="file"][name="image"]');
      const hasImage = fileInput && fileInput.files && fileInput.files.length > 0;
      // Enter envia se houver texto OU uma imagem anexada (print colado).
      if (ta.value.trim().length > 0 || hasImage) form.requestSubmit();
    }
  });

  // Colar imagem (Ctrl+V): captura um print da área de transferência e o
  // injeta no input de arquivo, reaproveitando a validação/preview. Sem isso
  // o browser ignora a imagem colada num textarea.
  ta.addEventListener('paste', e => {
    const items = (e.clipboardData && e.clipboardData.items) || [];
    for (const item of items) {
      if (item.kind === 'file' && item.type.startsWith('image/')) {
        const blob = item.getAsFile();
        if (!blob) continue;
        e.preventDefault();
        const form = ta.closest('.composer');
        const input = form && form.querySelector('input[type="file"][name="image"]');
        if (!input) return;
        const ext = (blob.type.split('/')[1] || 'png').split('+')[0];
        const file = new File([blob], 'print-' + Date.now() + '.' + ext,
            { type: blob.type });
        // DataTransfer é a única forma de setar input.files programaticamente.
        const dt = new DataTransfer();
        dt.items.add(file);
        input.files = dt.files;
        AcervoChat.previewImage(input);
        return;
      }
    }
  });
});

// Ao abrir/recarregar a página, posiciona thread e painel já no conteúdo mais
// recente (última resposta e última fonte). Reexecuta conforme fontes e
// imagens terminam de carregar, pois elas mudam a altura de forma assíncrona e
// invalidam a posição calculada antes — sem isso o usuário "cai" acima da
// resposta e precisa rolar pra baixo na mão.
(function positionLatest() {
  const run = () => AcervoChatScroll.toLatest();
  requestAnimationFrame(() => requestAnimationFrame(run));
  window.addEventListener('load', run);
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(run);
  document.querySelectorAll('.thread-scroll img').forEach(img => {
    if (!img.complete) img.addEventListener('load', run, { once: true });
  });
})();

// Polling AJAX da página de importação. Atualiza banner de progresso, status
// dos documentos, mostra/esconde barra indeterminada, habilita/desabilita
// botões "Tentar novamente". Não recarrega a página — só faz fetch + DOM patch.
window.AcervoImportStatus = {
  POLL_MS: 2500,
  TICK_MS: 1000,
  timer: null,
  ticker: null,
  avgSeconds: 0,

  start() {
    const el = document.getElementById('ingest-progress');
    if (!el) return;
    const subjectId = el.dataset.subjectId;
    if (!subjectId) return;
    this.subjectId = subjectId;
    this.tick();
    // Atualiza relógios (PROCESSING + total) a cada 1s sem hit no servidor.
    this.ticker = setInterval(() => this.tickClocks(), this.TICK_MS);
    this.tickClocks();
  },

  schedule(processing) {
    clearTimeout(this.timer);
    // Só fica em polling rápido enquanto há doc em processing.
    // Quando tudo terminou, para — usuário pode F5 se quiser revalidar.
    if (processing > 0) {
      this.timer = setTimeout(() => this.tick(), this.POLL_MS);
    }
  },

  async tick() {
    try {
      const res = await fetch('/import/' + this.subjectId + '/status', {
        headers: { 'Accept': 'application/json' }
      });
      if (!res.ok) { this.schedule(1); return; }
      const data = await res.json();
      this.apply(data);
      this.schedule(data.processingDocs);
    } catch (e) {
      this.schedule(1);
    }
  },

  apply(data) {
    const total = data.totalDocs || 0;
    const processing = data.processingDocs || 0;
    const indexed = data.indexedDocs || 0;
    const failed = data.failedDocs || 0;
    this.avgSeconds = data.avgSeconds || 0;

    const banner = document.getElementById('ingest-progress');
    if (banner) {
      banner.classList.toggle('active', processing > 0);
      const pct = total > 0 ? Math.round(indexed * 100 / total) : 0;
      const pctEl = document.getElementById('ip-pct');
      const fillEl = document.getElementById('ip-fill');
      const labelEl = document.getElementById('ip-label');
      if (pctEl) pctEl.textContent = pct + '%';
      if (fillEl) fillEl.style.width = pct + '%';
      if (labelEl) {
        if (processing > 0) {
          labelEl.textContent = 'Indexando… ' + indexed + ' de ' + total + ' concluídos';
        } else if (failed > 0) {
          labelEl.textContent = 'Processados ' + indexed + ' de ' + total +
              ' · ' + failed + ' com falha';
        } else {
          labelEl.textContent = 'Todos os ' + total + ' documentos processados.';
        }
      }
    }

    (data.documents || []).forEach(d => this.applyDoc(d, processing));
    AcervoFilter.updateCounts(data.documents || []);
    AcervoFilter.apply();
    this.tickClocks();
  },

  applyDoc(d, processing) {
    const row = document.getElementById('doc-' + d.id);
    if (!row) return;
    const prev = row.dataset.status;
    if (prev !== d.status) {
      row.dataset.status = d.status;

      // Status text/cor.
      const st = row.querySelector('.doc-status');
      if (st) {
        st.classList.remove('status-processing', 'status-indexed',
                            'status-failed', 'status-cancelled');
        st.classList.add('status-' + d.status.toLowerCase());
        st.textContent = this.statusLabel(d.status);
      }

      // Atualiza data-attrs de timestamp pro tickClocks usar.
      if (d.uploadedAt) row.dataset.uploadedAt = d.uploadedAt;
      row.dataset.processedAt = d.processedAt || '';

      // Barra indeterminada: só em PROCESSING.
      let bar = row.querySelector('.doc-indeterminate');
      if (d.status === 'PROCESSING' && !bar) {
        bar = document.createElement('div');
        bar.className = 'doc-indeterminate';
        bar.innerHTML = '<div class="di-bar"></div>';
        const grow = row.querySelector('.grow') || row;
        const errEl = grow.querySelector('.doc-error');
        if (errEl) grow.insertBefore(bar, errEl); else grow.appendChild(bar);
      } else if (d.status !== 'PROCESSING' && bar) {
        bar.remove();
      }

      // Mensagem de erro.
      const err = row.querySelector('.doc-error');
      if (err) {
        if (d.status === 'FAILED' && d.failureReason) {
          err.textContent = d.failureReason;
          err.style.display = '';
        } else {
          err.textContent = '';
          err.style.display = 'none';
        }
      }

      // Botões retry/stop: sempre dentro de .doc-actions.
      let actions = row.querySelector('.doc-actions');
      if (!actions) {
        actions = document.createElement('div');
        actions.className = 'doc-actions';
        row.appendChild(actions);
      }
      let retryBtn = actions.querySelector('.btn-retry');
      let resumeBtn = actions.querySelector('.btn-resume');
      let stopBtn = actions.querySelector('.btn-stop');

      if (d.status === 'FAILED' && !retryBtn) {
        retryBtn = document.createElement('button');
        retryBtn.type = 'button';
        retryBtn.className = 'btn-retry';
        retryBtn.dataset.docId = d.id;
        retryBtn.title = 'Tentar indexar novamente';
        retryBtn.textContent = '↻ Tentar de novo';
        retryBtn.addEventListener('click', () => AcervoRetry.click(retryBtn));
        actions.insertBefore(retryBtn, actions.firstChild);
      } else if (d.status !== 'FAILED' && retryBtn) {
        retryBtn.remove();
      }

      if (d.status === 'CANCELLED' && !resumeBtn) {
        resumeBtn = document.createElement('button');
        resumeBtn.type = 'button';
        resumeBtn.className = 'btn-resume';
        resumeBtn.dataset.docId = d.id;
        resumeBtn.title = 'Reiniciar indexação';
        resumeBtn.textContent = '▶ Reiniciar';
        resumeBtn.addEventListener('click', () => AcervoRetry.click(resumeBtn));
        actions.insertBefore(resumeBtn, actions.firstChild);
      } else if (d.status !== 'CANCELLED' && resumeBtn) {
        resumeBtn.remove();
      }
      if (d.status === 'PROCESSING' && !stopBtn) {
        stopBtn = document.createElement('button');
        stopBtn.type = 'button';
        stopBtn.className = 'btn-stop';
        stopBtn.dataset.docId = d.id;
        stopBtn.title = 'Parar indexação';
        stopBtn.textContent = '■ Parar';
        stopBtn.addEventListener('click', () => AcervoStop.click(stopBtn));
        actions.insertBefore(stopBtn, actions.firstChild);
      } else if (d.status !== 'PROCESSING' && stopBtn) {
        stopBtn.remove();
      }
    }

    // Páginas (preenchido quando indexação termina).
    if (d.pages != null) {
      const pgs = row.querySelector('.doc-pages');
      if (pgs && !pgs.textContent.trim()) pgs.textContent = d.pages + ' págs';
    }

    // Desabilita retry/resume enquanto há qualquer processing.
    const retry = row.querySelector('.btn-retry');
    if (retry) retry.disabled = processing > 0;
    const resume = row.querySelector('.btn-resume');
    if (resume) resume.disabled = processing > 0;
  },

  statusLabel(s) {
    if (s === 'PROCESSING') return 'Processando';
    if (s === 'INDEXED')    return 'Indexado';
    if (s === 'FAILED')     return 'Falha';
    if (s === 'CANCELLED')  return 'Cancelado';
    return s;
  },

  // Atualiza relógios sem requisição. Roda 1x/s e a cada poll.
  tickClocks() {
    const now = Date.now();
    const rows = document.querySelectorAll('.doc-row, .doc-card');
    let processingCount = 0;
    let earliestProcessing = Infinity;

    rows.forEach(row => {
      const status = row.dataset.status;
      const uploaded = parseInt(row.dataset.uploadedAt || '0', 10);
      const processed = parseInt(row.dataset.processedAt || '0', 10);
      const t = row.querySelector('.doc-timer');

      if (!t) return;

      if (status === 'PROCESSING' && uploaded) {
        processingCount++;
        if (uploaded < earliestProcessing) earliestProcessing = uploaded;
        t.textContent = this.fmtDuration((now - uploaded) / 1000);
        t.classList.add('running');
      } else if (status === 'INDEXED' && uploaded && processed) {
        t.textContent = this.fmtDuration((processed - uploaded) / 1000);
        t.classList.remove('running');
      } else if ((status === 'FAILED' || status === 'CANCELLED') && uploaded && processed) {
        t.textContent = this.fmtDuration((processed - uploaded) / 1000);
        t.classList.remove('running');
      } else {
        t.textContent = '—';
        t.classList.remove('running');
      }
    });

    const elapsedEl = document.getElementById('ip-elapsed');
    const avgEl = document.getElementById('ip-avg');
    const etaEl = document.getElementById('ip-eta');
    if (elapsedEl) {
      if (processingCount > 0 && earliestProcessing < Infinity) {
        elapsedEl.textContent = 'tempo: ' + this.fmtDuration((now - earliestProcessing) / 1000);
      } else {
        elapsedEl.textContent = 'tempo: —';
      }
    }
    if (avgEl) {
      avgEl.textContent = this.avgSeconds > 0
          ? 'média: ' + this.fmtDuration(this.avgSeconds) + '/doc'
          : 'média: —';
    }
    if (etaEl) {
      if (processingCount > 0 && this.avgSeconds > 0) {
        etaEl.textContent = 'faltam: ~' + this.fmtDuration(processingCount * this.avgSeconds);
      } else {
        etaEl.textContent = 'faltam: —';
      }
    }
  },

  fmtDuration(seconds) {
    if (!isFinite(seconds) || seconds < 0) return '—';
    const s = Math.floor(seconds);
    if (s < 60) return s + 's';
    const m = Math.floor(s / 60);
    const r = s % 60;
    if (m < 60) return m + 'm ' + r + 's';
    const h = Math.floor(m / 60);
    return h + 'h ' + (m % 60) + 'm';
  }
};

window.AcervoRetry = {
  async click(btn) {
    if (btn.disabled) return;
    const id = btn.dataset.docId;
    if (!id) return;
    btn.disabled = true;
    const prev = btn.textContent;
    btn.textContent = 'Enviando…';
    try {
      const res = await fetch('/import/documents/' + id + '/retry', {
        method: 'POST', headers: AcervoCsrf.headers()
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        AcervoModal.info(body.error || 'Não foi possível reenviar agora.');
        btn.textContent = prev;
        btn.disabled = false;
        return;
      }
      AcervoImportStatus.tick();
    } catch (e) {
      AcervoModal.info('Erro de rede. Tente de novo em instantes.');
      btn.textContent = prev;
      btn.disabled = false;
    }
  }
};

window.AcervoStop = {
  async click(btn) {
    if (btn.disabled) return;
    const id = btn.dataset.docId;
    if (!id) return;
    btn.disabled = true;
    btn.textContent = 'Parando…';
    try {
      const res = await fetch('/import/documents/' + id + '/cancel', {
        method: 'POST', headers: AcervoCsrf.headers()
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        AcervoModal.info(body.error || 'Não foi possível parar agora.');
        btn.disabled = false;
        btn.textContent = '■ Parar';
        return;
      }
      AcervoImportStatus.tick();
    } catch (e) {
      AcervoModal.info('Erro de rede.');
      btn.disabled = false;
      btn.textContent = '■ Parar';
    }
  }
};

window.AcervoFilter = {
  current: 'all',

  set(filter, btn) {
    this.current = filter;
    document.querySelectorAll('.doc-filters .chip').forEach(c => {
      c.classList.toggle('active', c.dataset.filter === filter);
    });
    this.apply();
  },

  apply() {
    document.querySelectorAll('.doc-row, .doc-card').forEach(row => {
      const status = row.dataset.status || '';
      const match = (this.current === 'all') || (status === this.current);
      row.classList.toggle('filtered', !match);
    });
  },

  updateCounts(docs) {
    const counts = { all: docs.length, INDEXED: 0, PROCESSING: 0, FAILED: 0, CANCELLED: 0 };
    docs.forEach(d => { if (counts[d.status] != null) counts[d.status]++; });
    document.querySelectorAll('.chip-count').forEach(el => {
      const key = el.dataset.count;
      if (counts[key] != null) el.textContent = counts[key];
    });
  }
};

// Inicializa contagens com o estado já renderizado no SSR.
document.addEventListener('DOMContentLoaded', () => {
  const rows = document.querySelectorAll('.doc-row, .doc-card');
  const docs = Array.from(rows).map(r => ({ status: r.dataset.status }));
  AcervoFilter.updateCounts(docs);
});

AcervoImportStatus.start();

// Toggle do form inline de renomear matéria.
// O id da matéria vem do atributo data-subject-id do botão — necessário porque
// o Thymeleaf 3.1 bloqueia strings dentro de th:onclick por segurança.
window.AcervoSubjects = {
  toggleRename(e, btn) {
    e.preventDefault();
    e.stopPropagation();
    const id = btn.dataset.subjectId;
    const form = document.getElementById('rename-' + id);
    if (!form) return;
    const open = form.classList.toggle('open');
    btn.setAttribute('aria-expanded', open);
    if (open) {
      const input = form.querySelector('input[name="name"]');
      input.focus();
      input.select();
    }
  },
  cancelRename(btn) {
    const id = btn.dataset.subjectId;
    const form = document.getElementById('rename-' + id);
    if (!form) return;
    form.classList.remove('open');
    const trigger = document.querySelector('[aria-controls="rename-' + id + '"]');
    if (trigger) {
      trigger.setAttribute('aria-expanded', 'false');
      trigger.focus();
    }
  }
};

// Modal de confirmação. Lê título/mensagem/label do botão dos atributos
// data-confirm-title / data-confirm / data-confirm-label da própria <form>.
// Necessário porque o Thymeleaf 3.1 bloqueia strings em onsubmit.
window.AcervoModal = {
  _onConfirm: null,

  show(opts, onConfirm) {
    const modal = document.getElementById('acervo-modal');
    if (!modal) return;
    document.getElementById('acervo-modal-title').textContent = opts.title || 'Confirmar';
    document.getElementById('acervo-modal-message').textContent = opts.message || '';
    const btn = document.getElementById('acervo-modal-confirm');
    btn.textContent = opts.confirmLabel || 'Confirmar';
    btn.className = opts.kind === 'primary' ? 'btn-primary' : 'btn-danger';
    const cancel = document.getElementById('acervo-modal-cancel');
    if (cancel) cancel.style.display = opts.hideCancel ? 'none' : '';
    this._onConfirm = onConfirm;
    modal.hidden = false;
    modal.setAttribute('aria-hidden', 'false');
    document.body.style.overflow = 'hidden';
    setTimeout(() => btn.focus(), 0);
  },

  info(message, title) {
    this.show({
      title: title || 'Aviso',
      message,
      confirmLabel: 'OK',
      kind: 'primary',
      hideCancel: true
    }, () => this.hide());
  },

  hide() {
    const modal = document.getElementById('acervo-modal');
    if (!modal) return;
    modal.hidden = true;
    modal.setAttribute('aria-hidden', 'true');
    this._onConfirm = null;
    document.body.style.overflow = '';
  },

  confirmSubmit(e, form) {
    e.preventDefault();
    this.show({
      title:        form.dataset.confirmTitle || 'Confirmar',
      message:      form.dataset.confirm      || 'Tem certeza?',
      confirmLabel: form.dataset.confirmLabel || 'Confirmar',
      kind: 'danger'
    }, () => { this.hide(); form.submit(); });
    return false;
  }
};

// Wiring do modal: botão confirmar, clique no backdrop e Escape.
const acervoModalConfirmBtn = document.getElementById('acervo-modal-confirm');
if (acervoModalConfirmBtn) {
  acervoModalConfirmBtn.addEventListener('click', () => {
    if (window.AcervoModal._onConfirm) window.AcervoModal._onConfirm();
  });
}
const acervoModalEl = document.getElementById('acervo-modal');
if (acervoModalEl) {
  acervoModalEl.addEventListener('click', e => {
    if (e.target === acervoModalEl) window.AcervoModal.hide();
  });
}
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') {
    const m = document.getElementById('acervo-modal');
    if (m && !m.hidden) window.AcervoModal.hide();
  }
});

// Dropdown <details>: clicar fora ou apertar Escape fecha.
document.querySelectorAll('details.subject-dropdown, details.user-dropdown').forEach(d => {
  document.addEventListener('click', e => {
    if (d.open && !d.contains(e.target)) d.open = false;
  });
  d.addEventListener('keydown', e => {
    if (e.key === 'Escape' && d.open) { d.open = false; d.querySelector('summary').focus(); }
  });
});

// ============ FASE 3 — UX e produtividade ============

// Modo tela cheia: colapsa sidebar + histórico + painel de fontes pra dar
// mais espaço de leitura ao thread. Toggle via botão no header ou tecla F.
window.AcervoFullscreen = {
  STORAGE_KEY: 'acervo.fullscreen',

  toggle() {
    const on = document.body.classList.toggle('chat-fullscreen');
    try { localStorage.setItem(this.STORAGE_KEY, on ? '1' : '0'); } catch (_) {}
  },

  restore() {
    try {
      if (localStorage.getItem(this.STORAGE_KEY) === '1') {
        document.body.classList.add('chat-fullscreen');
      }
    } catch (_) {}
  }
};
AcervoFullscreen.restore();

// Modal de atalhos.
window.AcervoShortcuts = {
  show() {
    const m = document.getElementById('shortcuts-modal');
    if (!m) return;
    m.hidden = false;
    m.setAttribute('aria-hidden', 'false');
    const btn = m.querySelector('.btn-outline');
    if (btn) setTimeout(() => btn.focus(), 0);
  },
  hide() {
    const m = document.getElementById('shortcuts-modal');
    if (!m) return;
    m.hidden = true;
    m.setAttribute('aria-hidden', 'true');
  }
};

// Modal de citação — mostra o trecho citado destacado, ao clicar no painel
// de fontes. Os dados vêm dos data-attrs do botão clicado.
window.AcervoCitation = {
  show(btn) {
    const m = document.getElementById('citation-modal');
    if (!m || !btn) return;
    const docName = btn.dataset.doc || '';
    const ext = btn.dataset.ext || '';
    const page = btn.dataset.page || '';
    const rel = btn.dataset.relevance || '';
    const excerpt = btn.dataset.excerpt || '';
    const question = btn.dataset.question || '';

    document.getElementById('citation-modal-title').textContent = docName;
    const badge = document.getElementById('citation-modal-badge');
    badge.textContent = ext;
    badge.className = 'badge ext-' + ext;

    const pageEl = document.getElementById('citation-modal-page');
    pageEl.textContent = page ? '📄 ' + page : '';
    pageEl.style.display = page ? '' : 'none';

    const relEl = document.getElementById('citation-modal-relevance');
    relEl.textContent = rel ? '★ relevância ' + rel + '%' : '';

    const qEl = document.getElementById('citation-modal-question');
    qEl.textContent = question;
    qEl.parentElement.style.display = question ? '' : 'none';

    document.getElementById('citation-modal-excerpt').textContent = excerpt;

    m.hidden = false;
    m.setAttribute('aria-hidden', 'false');
    document.body.style.overflow = 'hidden';
    const close = m.querySelector('.btn-outline');
    if (close) setTimeout(() => close.focus(), 0);
  },
  hide() {
    const m = document.getElementById('citation-modal');
    if (!m) return;
    m.hidden = true;
    m.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = '';
  }
};

// Backdrop click pra fechar os modais.
document.querySelectorAll('#shortcuts-modal, #citation-modal').forEach(m => {
  m.addEventListener('click', e => {
    if (e.target === m) {
      if (m.id === 'shortcuts-modal') AcervoShortcuts.hide();
      else AcervoCitation.hide();
    }
  });
});

// Busca client-side no histórico. Filtra .history-item por title + concat
// das mensagens (em data-search). Case-insensitive, sem regex.
window.AcervoHistorySearch = {
  filter(raw) {
    const q = (raw || '').trim().toLowerCase();
    const items = document.querySelectorAll('.history .history-item');
    let visible = 0;
    items.forEach(it => {
      if (!q) {
        it.classList.remove('search-hidden');
        visible++;
        return;
      }
      const hay = (it.dataset.search || '').toLowerCase();
      const match = hay.includes(q);
      it.classList.toggle('search-hidden', !match);
      if (match) visible++;
    });
    const empty = document.getElementById('history-empty-results');
    if (empty) empty.hidden = (visible > 0 || items.length === 0);
  }
};

// Atalhos globais. Não interfere quando o foco está num input/textarea
// — exceto Esc (que tem sentido em qualquer lugar) e Ctrl+algo.
window.AcervoShortcutHandler = {
  isTypingTarget(el) {
    if (!el) return false;
    const t = el.tagName;
    return t === 'INPUT' || t === 'TEXTAREA' || el.isContentEditable;
  },

  historyItems() {
    return Array.from(document.querySelectorAll('.history .history-item'))
        .filter(a => !a.classList.contains('search-hidden'));
  },

  navigateHistory(delta) {
    const items = this.historyItems();
    if (items.length === 0) return;
    const cur = document.activeElement && document.activeElement.classList
        && document.activeElement.classList.contains('history-item')
        ? document.activeElement : null;
    let idx;
    if (cur) {
      idx = items.indexOf(cur) + delta;
    } else {
      const active = items.find(a => a.classList.contains('active'));
      idx = (active ? items.indexOf(active) : -1) + delta;
    }
    if (idx < 0) idx = 0;
    if (idx >= items.length) idx = items.length - 1;
    items[idx].focus();
  }
};

document.addEventListener('keydown', e => {
  const handler = window.AcervoShortcutHandler;
  const typing = handler.isTypingTarget(e.target);

  // Ctrl/Cmd+K — abre o dropdown de matérias
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    const dd = document.querySelector('details.subject-dropdown');
    if (dd) {
      e.preventDefault();
      dd.open = true;
      const first = dd.querySelector('.dropdown-item');
      if (first) first.focus();
    }
    return;
  }

  // Ctrl/Cmd+/ — foca o composer
  if ((e.ctrlKey || e.metaKey) && e.key === '/') {
    const ta = document.querySelector('.composer textarea');
    if (ta) {
      e.preventDefault();
      ta.focus();
    }
    return;
  }

  // ↑ / ↓ — navega histórico (só fora de input)
  if (!typing && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) {
    const items = handler.historyItems();
    if (items.length > 0) {
      e.preventDefault();
      handler.navigateHistory(e.key === 'ArrowDown' ? 1 : -1);
    }
    return;
  }

  // F — tela cheia (só fora de input)
  if (!typing && (e.key === 'f' || e.key === 'F') && !e.ctrlKey && !e.metaKey && !e.altKey) {
    if (document.querySelector('.chat-body')) {
      e.preventDefault();
      AcervoFullscreen.toggle();
    }
    return;
  }

  // ? (Shift+/) — mostra ajuda (só fora de input)
  if (!typing && e.key === '?') {
    e.preventDefault();
    AcervoShortcuts.show();
    return;
  }

  // Esc — fecha modais novos
  if (e.key === 'Escape') {
    const sm = document.getElementById('shortcuts-modal');
    if (sm && !sm.hidden) { AcervoShortcuts.hide(); return; }
    const cm = document.getElementById('citation-modal');
    if (cm && !cm.hidden) { AcervoCitation.hide(); return; }
  }
});

// Escape dentro de um rename-subject fecha o form.
document.querySelectorAll('.rename-subject input[name="name"]').forEach(input => {
  input.addEventListener('keydown', e => {
    if (e.key === 'Escape') {
      const id = input.closest('form').id.replace('rename-', '');
      const trigger = document.querySelector('[aria-controls="rename-' + id + '"]');
      if (trigger) window.AcervoSubjects.cancelRename(trigger);
    }
  });
});

// ============ FASE 5 — Inteligência avançada ============

// Sumarização: dispara POST por nível, troca o conteúdo da caixa pela
// resposta. Mostra "Gerando…" no botão durante a chamada.
window.AcervoSummary = {
  async generate(btn, level) {
    const docId = btn.dataset.docId;
    if (!docId) return;
    const card = btn.closest('.summary-card');
    const contentEl = card.querySelector('.summary-content');
    const prevLabel = btn.textContent;
    btn.disabled = true;
    btn.textContent = 'Gerando…';
    try {
      const res = await fetch('/import/documents/' + docId + '/summary/' + level, {
        method: 'POST',
        headers: AcervoCsrf.headers()
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) {
        AcervoModal.info(body.error || 'Não foi possível gerar agora.');
      } else {
        contentEl.textContent = body.content || '(vazio)';
      }
    } catch (e) {
      AcervoModal.info('Erro de rede.');
    } finally {
      btn.disabled = false;
      btn.textContent = prevLabel;
    }
  }
};

// Flashcards: queue na ordem que veio do DOM. Mostra pergunta → resposta
// (toggle); ao avaliar, POSTa /flashcards/{id}/review?quality=N e avança.
window.AcervoFlashcards = {
  queue: [],
  index: 0,

  init() {
    const items = document.querySelectorAll('.flashcard-data .fc-item');
    if (items.length === 0) return;
    this.queue = Array.from(items).map(el => ({
      id: el.dataset.id,
      question: el.dataset.question,
      answer: el.dataset.answer,
      source: el.dataset.source
    }));
    this.index = 0;
    this.render();

    document.getElementById('fc-reveal').addEventListener('click', () => {
      this.reveal();
    });
    document.querySelectorAll('.flashcard-grades .btn-grade').forEach(b => {
      b.addEventListener('click', () => this.grade(parseInt(b.dataset.quality, 10)));
    });
  },

  render() {
    const card = this.queue[this.index];
    if (!card) return this.finish();
    document.getElementById('fc-counter').textContent =
        (this.index + 1) + ' / ' + this.queue.length;
    document.getElementById('fc-question').textContent = card.question;
    document.getElementById('fc-answer').textContent = card.answer;
    document.getElementById('fc-source').textContent = card.source || '';
    document.querySelector('.flashcard-question').hidden = false;
    document.querySelector('.flashcard-answer').hidden = true;
  },

  reveal() {
    document.querySelector('.flashcard-question').hidden = true;
    document.querySelector('.flashcard-answer').hidden = false;
  },

  async grade(quality) {
    const card = this.queue[this.index];
    if (!card) return;
    try {
      await fetch('/flashcards/' + card.id + '/review?quality=' + quality, {
        method: 'POST',
        headers: AcervoCsrf.headers()
      });
    } catch (e) { /* mesmo se falhar, avança */ }
    this.index++;
    this.render();
  },

  finish() {
    const review = document.getElementById('flashcard-review');
    if (!review) return;
    review.innerHTML = '<div class="profile-saved" role="status">' +
        'Revisão concluída! Volte mais tarde pra novos cards.' +
        '</div>';
  }
};

if (document.getElementById('flashcard-review')) {
  AcervoFlashcards.init();
}

// Quiz: usuário clica em opção; valida via /quiz/{id}/answer e mostra
// feedback (verde/vermelho) com explicação.
window.AcervoQuiz = {
  async select(btn) {
    const item = btn.closest('.quiz-item');
    if (!item || item.dataset.answered === 'true') return;
    const id = item.dataset.id;
    const index = parseInt(btn.dataset.index, 10);
    if (!id || isNaN(index)) return;
    item.dataset.answered = 'true';
    try {
      const res = await fetch('/quiz/' + id + '/answer?selected=' + index, {
        method: 'POST',
        headers: AcervoCsrf.headers()
      });
      const body = await res.json().catch(() => ({}));
      const correctIndex = body.correctIndex;
      const options = item.querySelectorAll('.quiz-option');
      options.forEach((o, i) => {
        if (i === correctIndex) o.classList.add('quiz-correct');
        else if (i === index) o.classList.add('quiz-wrong');
        o.disabled = true;
      });
      const fb = item.querySelector('.quiz-feedback');
      fb.hidden = false;
      const verdict = body.correct ? 'Correto.' : 'Incorreto.';
      fb.innerHTML =
          '<strong class="' + (body.correct ? 'fb-ok' : 'fb-bad') + '">'
          + verdict + '</strong> ' + (body.explanation || '') +
          (body.source ? '<div class="fb-source">Fonte: ' + body.source + '</div>' : '');
    } catch (e) {
      AcervoModal.info('Erro de rede ao validar resposta.');
      item.dataset.answered = 'false';
    }
  }
};

// Voz: ditar pergunta com Web Speech API + reproduzir resposta com
// SpeechSynthesis (TTS local do browser). Mic button no composer.
window.AcervoVoice = {
  recognition: null,
  recognizing: false,

  isSupported() {
    return typeof (window.SpeechRecognition || window.webkitSpeechRecognition) !== 'undefined';
  },

  ensureRecognition() {
    if (this.recognition) return this.recognition;
    const Cls = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!Cls) return null;
    this.recognition = new Cls();
    this.recognition.lang = 'pt-BR';
    this.recognition.continuous = false;
    this.recognition.interimResults = true;
    return this.recognition;
  },

  toggle(btn) {
    const rec = this.ensureRecognition();
    if (!rec) {
      AcervoModal.info('Seu navegador não suporta reconhecimento de voz.');
      return;
    }
    if (this.recognizing) {
      rec.stop();
      return;
    }
    const textarea = btn.closest('.composer').querySelector('textarea');
    if (!textarea) return;
    const startText = textarea.value;
    rec.onresult = (e) => {
      let transcript = '';
      for (let i = e.resultIndex; i < e.results.length; i++) {
        transcript += e.results[i][0].transcript;
      }
      textarea.value = startText
          ? (startText + (startText.endsWith(' ') ? '' : ' ') + transcript)
          : transcript;
      textarea.dispatchEvent(new Event('input'));
    };
    rec.onstart = () => { this.recognizing = true; btn.classList.add('recording'); };
    rec.onend = () => { this.recognizing = false; btn.classList.remove('recording'); };
    rec.onerror = () => { this.recognizing = false; btn.classList.remove('recording'); };
    rec.start();
  },

  speak(text) {
    if (!('speechSynthesis' in window)) return;
    const u = new SpeechSynthesisUtterance(text);
    u.lang = 'pt-BR';
    u.rate = 1.0;
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(u);
  },

  stopSpeaking() {
    if ('speechSynthesis' in window) window.speechSynthesis.cancel();
  }
};

// TTS streaming: lê a resposta do assistente em voz alta sentença por sentença
// enquanto chegam tokens via SSE. Estado global de mute persiste em localStorage
// e cada bolha de resposta tem um botão de alto-falante que reflete/alterna o
// estado. Browsers TTS engueuam utterances naturalmente; cancel() só é chamado
// ao silenciar pra interromper o que estava no meio.
window.AcervoTts = {
  STORAGE_KEY: 'acervo.tts.muted',
  VOICE_KEY: 'acervo.tts.voice',
  buffer: '',

  isSupported() {
    return 'speechSynthesis' in window;
  },

  isMuted() {
    try { return localStorage.getItem(this.STORAGE_KEY) === '1'; }
    catch (_) { return false; }
  },

  selectedVoiceName() {
    try { return localStorage.getItem(this.VOICE_KEY) || ''; }
    catch (_) { return ''; }
  },

  init() {
    if (!this.isSupported()) {
      document.body.classList.add('tts-unsupported');
      return;
    }
    this.applyMutedClass(this.isMuted());
    // getVoices() pode retornar lista vazia no primeiro tick — algumas
    // engines populam de forma assíncrona via 'voiceschanged'.
    this.populateVoiceSelects();
    window.speechSynthesis.onvoiceschanged = () => this.populateVoiceSelects();
  },

  applyMutedClass(muted) {
    document.body.classList.toggle('tts-muted', muted);
    document.querySelectorAll('.tts-toggle').forEach(b => {
      b.setAttribute('aria-pressed', muted ? 'true' : 'false');
      b.setAttribute('title', muted
          ? 'Ativar leitura por voz'
          : 'Silenciar leitura por voz');
    });
  },

  toggle() {
    const next = !this.isMuted();
    try {
      if (next) localStorage.setItem(this.STORAGE_KEY, '1');
      else localStorage.removeItem(this.STORAGE_KEY);
    } catch (_) {}
    this.applyMutedClass(next);
    if (next) {
      this.buffer = '';
      if (this.isSupported()) window.speechSynthesis.cancel();
    }
  },

  setVoice(name) {
    try {
      if (name) localStorage.setItem(this.VOICE_KEY, name);
      else localStorage.removeItem(this.VOICE_KEY);
    } catch (_) {}
    // Reflete a escolha em qualquer outro select aberto (ex.: outra aba do menu)
    document.querySelectorAll('.user-menu-voice').forEach(sel => {
      if (sel.value !== (name || '')) sel.value = name || '';
    });
  },

  populateVoiceSelects() {
    if (!this.isSupported()) return;
    const voices = window.speechSynthesis.getVoices() || [];
    if (voices.length === 0) return;
    // Ordena por idioma (pt primeiro) e nome
    const sorted = voices.slice().sort((a, b) => {
      const ap = (a.lang || '').toLowerCase().startsWith('pt') ? 0 : 1;
      const bp = (b.lang || '').toLowerCase().startsWith('pt') ? 0 : 1;
      if (ap !== bp) return ap - bp;
      return (a.name || '').localeCompare(b.name || '');
    });
    const saved = this.selectedVoiceName();
    document.querySelectorAll('.user-menu-voice').forEach(sel => {
      // Limpa e repopula. Mantém um "padrão do sistema" como primeira opção.
      sel.innerHTML = '';
      const def = document.createElement('option');
      def.value = '';
      def.textContent = 'Padrão do sistema';
      sel.appendChild(def);
      sorted.forEach(v => {
        const opt = document.createElement('option');
        opt.value = v.name;
        opt.textContent = v.name + ' (' + (v.lang || '?') + ')';
        sel.appendChild(opt);
      });
      sel.value = saved;
    });
  },

  resolveVoice() {
    if (!this.isSupported()) return null;
    const name = this.selectedVoiceName();
    if (!name) return null;
    const voices = window.speechSynthesis.getVoices() || [];
    return voices.find(v => v.name === name) || null;
  },

  resetStream() {
    this.buffer = '';
    if (this.isSupported()) window.speechSynthesis.cancel();
  },

  streamAppend(chunk) {
    if (this.isMuted() || !this.isSupported() || !chunk) return;
    this.buffer += chunk;
    // Extrai sentenças completas: tudo até [.!?\n] (inclusive). Mantém o
    // resto no buffer pra próximo chunk ou pro streamEnd.
    const re = /[^.!?\n]*[.!?\n]+/g;
    let m;
    let lastIdx = 0;
    while ((m = re.exec(this.buffer)) !== null) {
      lastIdx = m.index + m[0].length;
      this.speakUtterance(m[0]);
    }
    if (lastIdx > 0) this.buffer = this.buffer.slice(lastIdx);
  },

  streamEnd() {
    if (!this.isSupported()) { this.buffer = ''; return; }
    if (this.isMuted()) { this.buffer = ''; return; }
    const rest = this.buffer.trim();
    this.buffer = '';
    if (rest.length > 0) this.speakUtterance(rest);
  },

  speakUtterance(text) {
    const t = (text || '').trim();
    if (!t) return;
    const u = new SpeechSynthesisUtterance(t);
    const voice = this.resolveVoice();
    if (voice) {
      u.voice = voice;
      u.lang = voice.lang || 'pt-BR';
    } else {
      u.lang = 'pt-BR';
    }
    u.rate = 1.0;
    window.speechSynthesis.speak(u);
  },

  // Chama cb quando a fila do speechSynthesis esvaziar (ou imediatamente, se
  // não houver TTS ativo / o usuário mutou). Útil pra atrasar navegação até
  // a fala terminar — senão o reload corta a voz no meio.
  whenIdle(cb) {
    if (!this.isSupported() || this.isMuted()) { cb(); return; }
    const ss = window.speechSynthesis;
    // Margem de segurança: timeout absoluto pra não travar a UI se algo der
    // errado com a engine de TTS.
    const giveUpAt = Date.now() + 90000;
    const tick = () => {
      if (Date.now() > giveUpAt) { cb(); return; }
      if (!ss.speaking && !ss.pending) { cb(); return; }
      setTimeout(tick, 250);
    };
    tick();
  }
};

document.addEventListener('DOMContentLoaded', () => AcervoTts.init());

// Tela /resumo — abre SSE pro endpoint de sumarização, mostra progresso em
// duas fases:
//   1) "Lendo documento" — antes do primeiro token. Como o OpenAI-compatible
//      do LM Studio não expõe a barra de prompt processing via API, fazemos
//      estimativa por tempo: ~175 tokens/s de prompt eval (medido empiricamente
//      no console). Sobe suave de 0 → 90% nessa janela; se ultrapassar, fica
//      pendurado em 90%.
//   2) "Gerando resposta" — primeiro token disparado. Sobe 90 → 99% à medida
//      que tokens chegam (escala log pra dar sensação de movimento mesmo em
//      respostas curtas). Fecha em 100% no evento `done`.
window.AcervoResumo = {
  // Velocidades observadas no LM Studio com qwen2.5-7b-instruct:
  //   prompt eval ≈ 175 tokens/segundo
  //   token decode ≈ 22 tokens/segundo  (não usamos, só estimamos resposta)
  PROMPT_TPS: 175,
  // Margem extra pra cobrir warm-up, encoding etc. (multiplicador)
  PROMPT_PADDING: 1.15,
  // Estimativa de tamanho típico da resposta (tokens) — usado pra escalar a
  // fase 2. Resumos giram em ~80–300 tokens.
  RESPONSE_TARGET: 220,

  start(btn, level) {
    const grid = btn.closest('.resumo-type-grid');
    const card = btn.closest('.resumo-type');
    if (!grid || !card) return;
    const docId = grid.dataset.docId;
    if (!docId) return;

    // Desabilita todos os botões enquanto roda — uma geração de cada vez.
    grid.querySelectorAll('.resumo-type .btn-primary').forEach(b => b.disabled = true);
    card.classList.add('running');

    const progress = card.querySelector('.resumo-progress');
    const phaseEl = progress.querySelector('.resumo-progress-phase');
    const pctEl = progress.querySelector('.resumo-progress-pct');
    const fillEl = progress.querySelector('.resumo-progress-fill');
    const metaEl = progress.querySelector('.resumo-progress-meta');
    const result = card.querySelector('.resumo-result');

    progress.hidden = false;
    result.hidden = true;
    result.textContent = '';
    phaseEl.textContent = 'Conectando…';
    pctEl.textContent = '0%';
    fillEl.style.width = '0%';
    metaEl.textContent = '';

    const state = {
      pct: 0,
      phase: 'prompt',        // 'prompt' | 'decode' | 'done'
      startedAt: Date.now(),
      promptStartedAt: null,
      expectedPromptMs: 8000, // ajustado quando recebermos o evento `info`
      tokensReceived: 0,
      accumulator: '',
      raf: null,
    };

    const setPct = (next) => {
      const clamped = Math.max(state.pct, Math.min(100, next));
      state.pct = clamped;
      const display = Math.round(clamped);
      fillEl.style.width = display + '%';
      pctEl.textContent = display + '%';
    };

    const tick = () => {
      const now = Date.now();
      if (state.phase === 'prompt' && state.promptStartedAt) {
        const elapsed = now - state.promptStartedAt;
        const ratio = Math.min(elapsed / state.expectedPromptMs, 1);
        // Curva ease-out: rápido no começo, devagar no fim — combina com a
        // realidade do prompt processing.
        const eased = 1 - Math.pow(1 - ratio, 2.4);
        setPct(eased * 90);
        const elapsedS = (elapsed / 1000).toFixed(1);
        metaEl.textContent = elapsedS + 's de leitura';
      } else if (state.phase === 'decode') {
        // 90 → 99% conforme tokens chegam. Curva log pra mostrar movimento
        // mesmo com respostas curtas.
        const t = state.tokensReceived;
        const fracion = 1 - Math.exp(-t / state.RESPONSE_TARGET);
        setPct(90 + 9 * fracion);
        metaEl.textContent = t + ' tokens recebidos';
      }
      state.raf = requestAnimationFrame(tick);
    };

    const stopTick = () => {
      if (state.raf) { cancelAnimationFrame(state.raf); state.raf = null; }
    };

    const finish = (text, ok) => {
      stopTick();
      state.phase = 'done';
      setPct(100);
      phaseEl.textContent = ok ? 'Resumo gerado' : 'Falha';
      const totalS = ((Date.now() - state.startedAt) / 1000).toFixed(1);
      metaEl.textContent = totalS + 's no total';
      result.hidden = false;
      result.textContent = text;
      grid.querySelectorAll('.resumo-type .btn-primary').forEach(b => b.disabled = false);
      btn.textContent = 'Regenerar';
      card.classList.remove('running');
      if (!ok) card.classList.add('failed');
    };

    state.RESPONSE_TARGET = this.RESPONSE_TARGET;

    const url = '/import/documents/' + encodeURIComponent(docId)
        + '/summary/' + encodeURIComponent(level) + '/stream';
    const es = new EventSource(url);

    es.addEventListener('info', ev => {
      try {
        const info = JSON.parse(ev.data || '{}');
        const promptTokens = Number(info.promptTokens) || 0;
        if (promptTokens > 0) {
          const seconds = (promptTokens / this.PROMPT_TPS) * this.PROMPT_PADDING;
          state.expectedPromptMs = Math.max(2000, seconds * 1000);
        }
      } catch (_) {}
      state.phase = 'prompt';
      state.promptStartedAt = Date.now();
      phaseEl.textContent = 'Lendo documento';
      state.raf = requestAnimationFrame(tick);
    });

    es.addEventListener('token', ev => {
      if (state.phase !== 'decode') {
        state.phase = 'decode';
        phaseEl.textContent = 'Gerando resumo';
        // Garante que a transição não pareça travada: pula direto pra 90%.
        setPct(90);
      }
      // Decodifica URL-encoding: o servidor encoda pra preservar espaços
      // líderes que o parser SSE removeria.
      let chunk = '';
      try { chunk = decodeURIComponent(ev.data || ''); }
      catch (_) { chunk = ev.data || ''; }
      if (!chunk) return;
      state.accumulator += chunk;
      // Aproximação: 1 token ≈ 4 chars. Suficiente pra animar a barra.
      state.tokensReceived = Math.max(state.tokensReceived + 1,
          Math.ceil(state.accumulator.length / 4));
      // Mostra texto parcial em tempo real
      result.hidden = false;
      result.textContent = state.accumulator;
    });

    es.addEventListener('done', () => {
      es.close();
      finish(state.accumulator, true);
    });

    es.addEventListener('error', ev => {
      // SSE dispara 'error' tanto pra falha quanto pra desconexão. Se já
      // recebemos texto, considera concluído (LM Studio às vezes encerra
      // sem flush do evento 'done').
      es.close();
      if (state.accumulator.length > 0) {
        finish(state.accumulator, true);
      } else {
        // Tenta extrair mensagem do servidor; senão genérica.
        const msg = (ev && ev.data) ? String(ev.data)
            : 'Não consegui gerar o resumo. Tente novamente em alguns segundos.';
        finish(msg, false);
      }
    });
  },

  // Troca o painel ativo no segmented control.
  selectTab(btn) {
    const tabs = btn.closest('.resumo-type-tabs');
    if (!tabs) return;
    const target = btn.getAttribute('data-target');
    tabs.querySelectorAll('.resumo-tab').forEach(b => {
      const active = b === btn;
      b.classList.toggle('active', active);
      b.setAttribute('aria-selected', active ? 'true' : 'false');
    });
    // O grid de painéis fica logo depois das tabs no DOM.
    let grid = tabs.nextElementSibling;
    while (grid && !grid.classList.contains('resumo-type-grid')) {
      grid = grid.nextElementSibling;
    }
    if (!grid) return;
    grid.querySelectorAll('.resumo-type').forEach(panel => {
      panel.hidden = panel.getAttribute('data-level') !== target;
    });
  },

  // Carrega resumos já gerados (do servidor) nos cards quando a página abre.
  init() {
    const existing = document.querySelector('.resumo-existing-data');
    if (!existing) return;
    existing.querySelectorAll('[data-level]').forEach(node => {
      const level = node.getAttribute('data-level');
      const content = node.getAttribute('data-content') || '';
      const card = document.querySelector('.resumo-type[data-level="' + level + '"]');
      if (!card || !content) return;
      const result = card.querySelector('.resumo-result');
      const btn = card.querySelector('.btn-primary');
      result.hidden = false;
      result.textContent = content;
      if (btn) btn.textContent = 'Regenerar';
      // Marca a tab correspondente como "já gerado"
      const tab = document.querySelector('.resumo-tab[data-target="' + level + '"]');
      if (tab) tab.classList.add('has-result');
    });
  }
};

document.addEventListener('DOMContentLoaded', () => AcervoResumo.init());
