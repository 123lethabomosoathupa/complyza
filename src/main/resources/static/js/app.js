(function () {
  'use strict';

  const $ = id => document.getElementById(id);
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c =>
    ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
  const el = (tag, cls, text) => {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined) n.textContent = text;
    return n;
  };
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  const SUGGESTIONS = [
    'When may personal information be transferred outside South Africa?',
    'What security measures must a responsible party take?',
    'When must a data breach be reported?',
    'What are the rules for direct marketing?'
  ];

  let acts = [];
  let current = null;
  let pendingSection = null;
  const cache = {};

  /* ---------- API ---------- */

  async function api(path, options) {
    const res = await fetch(path, options);
    let body = null;
    try { body = await res.json(); } catch (e) { /* not JSON */ }
    if (!res.ok) {
      const err = new Error((body && body.detail) || 'Request failed (' + res.status + ')');
      err.status = res.status;
      throw err;
    }
    if (body === null) {
      throw new Error('Your session may have expired. Reload the page and sign in again.');
    }
    return body;
  }

  /* ---------- library ---------- */

  async function loadActs() {
    try {
      acts = await api('/api/acts');
      renderActs();
    } catch (e) {
      $('act-list').innerHTML = '<p class="empty">Could not load the Acts: ' + esc(e.message) + '</p>';
    }
  }

  function renderActs() {
    const box = $('act-list');
    if (!acts.length) {
      box.innerHTML = '<p class="empty">No Acts are loaded yet. An administrator can add one with the ingest endpoint.</p>';
      return;
    }
    box.innerHTML = acts.map(a => `
      <button type="button" class="act" data-act="${esc(a.act)}">
        <span class="act-spine">${esc(a.act)}</span>
        <span class="act-body">
          <h3>${esc(a.name)}</h3>
          <p>${esc(a.summary)}</p>
          <span class="act-facts">${esc(a.sections)} sections &middot; ${esc(a.versionLabel)}</span>
        </span>
      </button>`).join('');
    box.querySelectorAll('.act').forEach(b =>
      b.addEventListener('click', () => { location.hash = encodeURIComponent(b.dataset.act); }));
  }

  /* ---------- routing ---------- */

  function route() {
    const key = decodeURIComponent(location.hash.slice(1));
    if (key) showAct(key); else showHome();
  }

  function showHome() {
    current = null;
    $('act-view').hidden = true;
    $('home').hidden = false;
    document.title = 'Comply ZA';
    window.scrollTo(0, 0);
  }

  async function showAct(key) {
    $('home').hidden = true;
    $('act-view').hidden = false;
    $('act-title').textContent = 'Loading\u2026';
    $('sections').innerHTML = '';
    try {
      if (!cache[key]) cache[key] = await api('/api/acts/' + encodeURIComponent(key));
      current = cache[key];
      renderAct(current);
      if (pendingSection) {
        const sec = pendingSection;
        pendingSection = null;
        focusSection(sec);
      } else {
        window.scrollTo(0, 0);
      }
    } catch (e) {
      $('act-title').textContent = 'This Act could not be opened';
      $('act-summary').textContent = e.status === 404
        ? 'It is not loaded. Go back to the list of Acts.'
        : e.message;
      $('act-abbr').textContent = '';
      $('act-meta').textContent = '';
    }
  }

  /* ---------- reading view ---------- */

  function renderAct(data) {
    document.title = data.name + ' | Comply ZA';
    $('act-abbr').textContent = data.act;
    $('act-title').textContent = data.name;
    $('act-summary').textContent = data.summary;
    $('act-meta').textContent = data.sectionCount + ' sections \u00b7 ' + data.versionLabel;
    $('section-search').value = '';

    const list = $('sections');
    list.innerHTML = '';
    list.classList.remove('filtering');
    let lastChapter = null;

    data.sections.forEach(s => {
      const chapter = s.chapter || '';
      if (chapter !== lastChapter) {
        lastChapter = chapter;
        if (chapter) {
          const title = s.chapterTitle ? ' \u2013 ' + s.chapterTitle : '';
          list.appendChild(el('h3', 'chapter', 'Chapter ' + chapter + title));
        }
      }
      const d = document.createElement('details');
      d.className = 'section';
      d.id = 'sec-' + s.section;
      d.dataset.search = (s.section + ' ' + s.heading + ' ' + s.text).toLowerCase();

      const sum = document.createElement('summary');
      sum.appendChild(el('span', 'sec-num', s.section));
      sum.appendChild(el('span', 'sec-title', s.heading || ('Section ' + s.section)));
      d.appendChild(sum);
      d.appendChild(el('div', 'section-text', s.text));
      list.appendChild(d);
    });
    applyFilter();
  }

  function applyFilter() {
    const q = $('section-search').value.trim().toLowerCase();
    const list = $('sections');
    const items = list.querySelectorAll('details.section');
    let shown = 0;
    list.classList.toggle('filtering', q !== '');
    items.forEach(d => {
      const match = q === '' || d.dataset.search.includes(q);
      d.hidden = !match;
      if (match) shown++;
    });
    if (q !== '') {
      items.forEach(d => { d.open = !d.hidden && shown <= 12; });
    }
    $('section-count').textContent = q === '' ? items.length + ' sections' : shown + ' of ' + items.length;
    $('no-match').hidden = shown !== 0;
  }

  function focusSection(sec) {
    const d = $('sec-' + sec);
    if (!d) return;
    if ($('section-search').value !== '') {
      $('section-search').value = '';
      applyFilter();
    }
    d.open = true;
    d.scrollIntoView({behavior: reduceMotion ? 'auto' : 'smooth', block: 'start'});
    d.classList.remove('flash');
    void d.offsetWidth;
    d.classList.add('flash');
  }

  function openSection(act, sec) {
    if (current && current.act === act) {
      focusSection(sec);
    } else {
      pendingSection = sec;
      location.hash = encodeURIComponent(act);
    }
  }

  /* ---------- assistant ---------- */

  let busy = false;
  let greeted = false;

  function setChat(open) {
    $('chat').hidden = !open;
    $('shell').classList.toggle('chat-open', open);
    $('chat-toggle').setAttribute('aria-expanded', String(open));
    if (open) {
      if (!greeted) {
        greeted = true;
        addBot(textNode('Ask a question about any Act on this site. Each answer is checked against the Act\u2019s own words, and every quote links to its section.'));
      }
      $('chat-input').focus();
    }
  }

  function textNode(text) {
    const wrap = el('div');
    wrap.textContent = text;
    return wrap;
  }

  function scrollLog() {
    const log = $('chat-log');
    log.scrollTop = log.scrollHeight;
  }

  function addUser(text) {
    $('chat-log').appendChild(el('div', 'msg user', text));
    scrollLog();
  }

  function addBot(node, cls) {
    const m = el('div', cls || 'msg bot');
    m.appendChild(node);
    $('chat-log').appendChild(m);
    scrollLog();
    return m;
  }

  function addTyping() {
    const t = el('span', 'typing');
    t.innerHTML = '<i></i><i></i><i></i>';
    t.setAttribute('aria-label', 'The assistant is working on an answer');
    return addBot(t);
  }

  function renderAnswer(r) {
    const wrap = el('div');
    const badge = el('span', 'badge ' + String(r.confidence).toLowerCase(),
      String(r.confidence).replace('_', ' ').toLowerCase());
    wrap.appendChild(badge);
    wrap.appendChild(el('p', 'answer-text', r.answer));

    const bySection = new Map();
    (r.citations || []).forEach(c => {
      const key = c.act + '|' + c.section;
      if (!bySection.has(key)) bySection.set(key, {act: c.act, section: c.section, quotes: []});
      bySection.get(key).quotes.push(c.quote);
    });

    if (bySection.size) {
      const chips = el('div', 'cites');
      bySection.forEach(v => {
        const b = el('button', 'cite-chip', v.act + ' section ' + v.section);
        b.type = 'button';
        b.addEventListener('click', () => openSection(v.act, v.section));
        chips.appendChild(b);
      });
      wrap.appendChild(chips);

      const q = document.createElement('details');
      q.className = 'quotes';
      q.appendChild(el('summary', null, 'Show the quoted text'));
      bySection.forEach(v => v.quotes.forEach(text => q.appendChild(el('blockquote', null, text))));
      wrap.appendChild(q);
    }
    if (r.rejectedCitations) {
      wrap.appendChild(el('p', 'note', r.rejectedCitations + ' citation(s) were removed because the quote did not match the source text.'));
    }
    const redactions = Object.entries(r.redactions || {});
    if (redactions.length) {
      wrap.appendChild(el('p', 'note', 'Personal information was removed from your question before it was processed.'));
    }
    return wrap;
  }

  async function ask(question) {
    const text = question.trim();
    if (!text || busy) return;
    busy = true;
    $('chat-send').disabled = true;
    $('chips').hidden = true;
    addUser(text);
    $('chat-input').value = '';
    const typing = addTyping();
    try {
      const r = await api('/api/ask', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({question: text})
      });
      typing.remove();
      addBot(renderAnswer(r));
    } catch (e) {
      typing.remove();
      addBot(textNode(e.message), 'msg error');
    } finally {
      busy = false;
      $('chat-send').disabled = false;
      $('chat-input').focus();
    }
  }

  function renderChips() {
    const box = $('chips');
    SUGGESTIONS.forEach(s => {
      const b = el('button', 'chip', s);
      b.type = 'button';
      b.addEventListener('click', () => ask(s));
      box.appendChild(b);
    });
  }

  /* ---------- wiring ---------- */

  $('chat-toggle').addEventListener('click', () => setChat($('chat').hidden));
  $('chat-close').addEventListener('click', () => { setChat(false); $('chat-toggle').focus(); });
  $('chat-form').addEventListener('submit', e => { e.preventDefault(); ask($('chat-input').value); });
  $('chat-input').addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); ask($('chat-input').value); }
  });

  $('section-search').addEventListener('input', applyFilter);
  $('expand-all').addEventListener('click', () => {
    $('sections').querySelectorAll('details.section:not([hidden])').forEach(d => { d.open = true; });
  });
  $('collapse-all').addEventListener('click', () => {
    $('sections').querySelectorAll('details.section').forEach(d => { d.open = false; });
  });

  function goHome(e) { e.preventDefault(); if (location.hash) location.hash = ''; else showHome(); }
  $('brand-link').addEventListener('click', goHome);
  $('back-link').addEventListener('click', goHome);

  window.addEventListener('hashchange', route);
  renderChips();
  loadActs();
  route();
})();
