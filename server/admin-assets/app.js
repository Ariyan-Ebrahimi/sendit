/* Sendit admin — the interactive layer on top of server-rendered pages.
 * Themes, sidebar, toasts + undo, confirm dialogs, smart tables, side panel, Ctrl+K, shortcuts,
 * charts, dashboard widgets, live badges, notifications, gallery/lightbox, waveforms, support inbox. */
(() => {
  'use strict';
  const A = window.ADMIN || { csrf: '', pages: [], actions: [], role: 'owner' };
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const store = {
    get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* private mode */ } }
  };
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const icon = (n, cls = '') => `<svg class="ic ${cls}"><use href="#i-${n}"/></svg>`;
  const html = (s) => { const t = document.createElement('template'); t.innerHTML = s.trim(); return t.content.firstElementChild; };
  const fmtBytes = (n) => { n = +n || 0; if (n < 1024) return n + ' B'; const u = ['KB', 'MB', 'GB', 'TB']; let v = n / 1024, i = 0; while (v >= 1024 && i < 3) { v /= 1024; i++; } return v.toFixed(v >= 100 ? 0 : 1) + ' ' + u[i]; };
  const fmt = (v, f) => (f === 'bytes' ? fmtBytes(v) : Number(v).toLocaleString());
  const ago = (d) => { const s = Math.round((Date.now() - new Date(d)) / 1000); return s < 60 ? 'just now' : s < 3600 ? Math.round(s / 60) + ' min ago' : s < 86400 ? Math.round(s / 3600) + ' h ago' : Math.round(s / 86400) + ' d ago'; };
  const post = (url, body) => fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF': A.csrf }, body: JSON.stringify(body || {}) });

  // ---------------- theme & sidebar ----------------
  const root = document.documentElement;
  function setTheme(t) {
    root.dataset.theme = t;
    store.set('adm-theme', t);
    $$('[data-act="theme"] use').forEach((u) => u.setAttribute('href', t === 'dark' ? '#i-sun' : '#i-moon'));
  }
  setTheme(root.dataset.theme || 'dark');
  if (store.get('adm-mini', false)) root.classList.add('mini');
  const side = $('.side'), scrim = $('.scrim');
  const closeSide = () => { side && side.classList.remove('open'); if (!$('.drawer.open')) scrim.classList.remove('on'); };

  // ---------------- toasts ----------------
  const toasts = $('.toasts');
  function toast(msg, opts = {}) {
    const t = html(`<div class="toast ${opts.kind || ''}"><div class="ti">${icon(opts.kind === 'err' ? 'x' : opts.kind === 'info' ? 'bell' : 'check', 'sm')}</div><span>${esc(msg)}</span></div>`);
    let timer, left = opts.undo ? opts.seconds || 6 : 0;
    if (opts.undo) {
      const b = html(`<button>Undo (${left})</button>`);
      const bar = html('<div class="tp"></div>');
      t.append(b, bar);
      bar.animate([{ width: '100%' }, { width: '0%' }], { duration: left * 1000, fill: 'forwards' });
      const tick = setInterval(() => { left--; b.textContent = `Undo (${left})`; if (left <= 0) { clearInterval(tick); t.remove(); opts.then && opts.then(); } }, 1000);
      b.onclick = () => { clearInterval(tick); t.remove(); toast('Cancelled — nothing changed.', { kind: 'info' }); };
    } else {
      timer = setTimeout(() => t.remove(), opts.ms || 4200);
      t.onclick = () => { clearTimeout(timer); t.remove(); };
    }
    toasts.append(t);
    return t;
  }
  // Server messages (?msg=…) arrive as toasts, then leave the address bar clean.
  const flash = $('main .flash');
  if (flash && flash.textContent.trim()) {
    const m = flash.textContent.trim();
    toast(m, { kind: /fail|error|wrong|not |can't|cannot|refused/i.test(m) ? 'err' : '', ms: /password for|New codes/.test(m) ? 20000 : 4500 });
  }
  if (location.search.includes('msg=')) {
    const u = new URL(location.href); u.searchParams.delete('msg'); history.replaceState(null, '', u.pathname + (u.search || '') + u.hash);
  }

  // ---------------- confirm dialog ----------------
  const modal = $('.modal');
  function ask({ title, text, type, ok = 'Continue', danger = true }) {
    return new Promise((resolve) => {
      modal.innerHTML = `${danger ? `<div class="mi">${icon('alert')}</div>` : ''}<h3>${esc(title)}</h3><p>${esc(text || '')}</p>
        ${type ? `<p>Type <b class="code">${esc(type)}</b> to confirm.</p><input autocomplete="off">` : ''}
        <div class="mb"><button data-r="0">Cancel</button><button data-r="1" class="${danger ? 'danger' : 'primary'}" ${type ? 'disabled' : ''}>${esc(ok)}</button></div>`;
      modal.classList.add('on'); scrim.classList.add('on');
      const input = $('input', modal), okBtn = $('[data-r="1"]', modal);
      if (input) { input.focus(); input.oninput = () => { okBtn.disabled = input.value.trim() !== type; }; } else okBtn.focus();
      const done = (v) => { modal.classList.remove('on'); if (!$('.drawer.open') && !side.classList.contains('open')) scrim.classList.remove('on'); document.removeEventListener('keydown', key, true); resolve(v); };
      const key = (e) => { if (e.key === 'Escape') { e.stopPropagation(); done(false); } if (e.key === 'Enter' && !okBtn.disabled) { e.preventDefault(); done(true); } };
      document.addEventListener('keydown', key, true);
      modal.onclick = (e) => { const r = e.target.closest('[data-r]'); if (r && !r.disabled) done(r.dataset.r === '1'); };
    });
  }

  // Forms: confirm → typed confirm → undo window → submit.
  document.addEventListener('submit', async (e) => {
    const f = e.target;
    if (f.dataset.ok === '1') return;
    if (f.dataset.bulk !== undefined) {
      e.preventDefault();
      const n = $$('input[name=ids]:checked', f).length;
      const action = $('select[name=action]', f).value;
      if (!n) return toast('Tick some users first.', { kind: 'err' });
      const label = $('select[name=action]', f).selectedOptions[0].textContent;
      if (action === 'delete' && !(await ask({ title: `Delete ${n} account(s)?`, text: 'Their files and history are removed for good.', type: 'DELETE', ok: 'Delete' }))) return;
      if (action !== 'delete' && !(await ask({ title: `${label} — ${n} user(s)?`, ok: label, danger: action === 'block' }))) return;
      f.dataset.ok = '1'; return f.submit();
    }
    const { confirm, type, undo } = f.dataset;
    if (!confirm && !type && !undo) return;
    e.preventDefault();
    if ((confirm || type) && !(await ask({ title: confirm || 'Are you sure?', type, ok: f.querySelector('button')?.textContent?.trim() || 'Continue', danger: !!(undo || type || f.querySelector('button.danger')) }))) return;
    const go = () => { f.dataset.ok = '1'; f.submit(); };
    if (undo && !type) toast(undo, { undo: true, then: go }); else go();
  });

  // ---------------- smart tables ----------------
  const parseVal = (s) => {
    s = s.trim();
    const b = /^(-?[\d,.]+)\s*(B|KB|MB|GB|TB)\b/i.exec(s);
    if (b) return parseFloat(b[1].replace(/,/g, '')) * Math.pow(1024, ['B', 'KB', 'MB', 'GB', 'TB'].indexOf(b[2].toUpperCase()));
    if (/^\d{4}-\d\d-\d\d/.test(s)) return s;
    const n = /^-?[\d,]+(\.\d+)?(?=\D|$)/.exec(s);
    return n ? parseFloat(n[0].replace(/,/g, '')) : s.toLowerCase();
  };
  function enhanceTable(t, idx) {
    if (t.dataset.enh || t.closest('[data-plain]')) return;
    t.dataset.enh = '1';
    const head = t.rows[0];
    if (!head || !head.querySelector('th')) return;
    const ths = [...head.cells];
    const all = [...t.rows].slice(1);
    all.forEach((r) => [...r.cells].forEach((c, i) => { if (ths[i] && !(r.cells.length === 1 && c.colSpan > 1)) c.dataset.label = ths[i].textContent.trim(); }));
    const rows = all.filter((r) => !(r.cells.length === 1 && r.cells[0].colSpan > 1));
    if (rows.length < 2) return;
    const tbody = rows[0].parentNode;
    const key = `adm-t:${location.pathname}:${idx}`;
    const st = Object.assign({ hidden: [], size: 50 }, store.get(key, {}));
    let page = 0, filter = '', sortCol = -1, dir = 1;
    ths.forEach((th, i) => {
      if (!th.textContent.trim()) return;
      th.classList.add('sortable');
      th.insertAdjacentHTML('beforeend', '<span class="arrow">▲▼</span>');
      th.onclick = () => {
        dir = sortCol === i ? -dir : 1; sortCol = i;
        ths.forEach((x) => x.classList.remove('asc', 'desc'));
        th.classList.add(dir > 0 ? 'asc' : 'desc');
        th.querySelector('.arrow').textContent = dir > 0 ? '▲' : '▼';
        rows.sort((a, b) => {
          const x = parseVal(a.cells[i]?.textContent || ''), y = parseVal(b.cells[i]?.textContent || '');
          return (typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y))) * dir;
        }).forEach((r) => tbody.appendChild(r));
        apply();
      };
    });
    const big = rows.length >= 8;
    let bar, pager, count;
    if (big) {
      bar = html(`<div class="tbar"><input placeholder="Filter this list…  ( / )" aria-label="Filter"><button type="button" class="cols">${icon('columns', 'sm')} Columns</button>
        <select aria-label="Rows per page">${[25, 50, 100, 0].map((n) => `<option value="${n}" ${n === st.size ? 'selected' : ''}>${n ? n + ' per page' : 'All rows'}</option>`).join('')}</select><span class="count"></span></div>`);
      t.before(bar);
      pager = html('<div class="pager"></div>');
      t.after(pager);
      count = $('.count', bar);
      $('input', bar).oninput = (e) => { filter = e.target.value.toLowerCase(); page = 0; apply(); };
      $('select', bar).onchange = (e) => { st.size = +e.target.value; store.set(key, st); page = 0; apply(); };
      $('.cols', bar).onclick = (e) => {
        e.stopPropagation();
        $$('.popmenu').forEach((m) => m.remove());
        const m = html(`<div class="popmenu">${ths.map((th, i) => th.textContent.replace(/[▲▼]/g, '').trim() ? `<label><input type="checkbox" data-c="${i}" ${st.hidden.includes(i) ? '' : 'checked'}> ${esc(th.textContent.replace(/[▲▼]/g, '').trim())}</label>` : '').join('')}</div>`);
        document.body.append(m);
        const r = e.currentTarget.getBoundingClientRect();
        m.style.top = r.bottom + scrollY + 6 + 'px'; m.style.left = Math.min(r.left, innerWidth - 220) + 'px';
        m.onchange = (ev) => { const c = +ev.target.dataset.c; st.hidden = ev.target.checked ? st.hidden.filter((x) => x !== c) : [...st.hidden, c]; store.set(key, st); cols(); };
        m.onclick = (ev) => ev.stopPropagation();
      };
    }
    function cols() { [...t.rows].forEach((r) => [...r.cells].forEach((c, i) => { if (r.cells.length > 1 || c.colSpan === 1) c.style.display = st.hidden.includes(i) ? 'none' : ''; })); }
    function apply() {
      const match = rows.filter((r) => !filter || r.textContent.toLowerCase().includes(filter));
      rows.forEach((r) => { r.style.display = 'none'; });
      const size = big && st.size ? st.size : match.length || 1;
      const pages = Math.max(1, Math.ceil(match.length / size));
      page = Math.min(page, pages - 1);
      match.slice(page * size, page * size + size).forEach((r) => { r.style.display = ''; });
      if (big) {
        count.textContent = `${match.length} of ${rows.length}`;
        pager.innerHTML = pages > 1 ? `<span>Page ${page + 1} of ${pages}</span><button type="button" data-p="-1" ${page ? '' : 'disabled'}>‹ Prev</button><button type="button" data-p="1" ${page < pages - 1 ? '' : 'disabled'}>Next ›</button>` : '';
        pager.onclick = (e) => { const b = e.target.closest('[data-p]'); if (b) { page += +b.dataset.p; apply(); t.scrollIntoView({ block: 'start', behavior: 'smooth' }); } };
      }
    }
    cols(); apply();
  }

  // ---------------- charts ----------------
  function drawChart(el) {
    const pts = JSON.parse(el.dataset.points || '[]');
    const prev = JSON.parse(el.dataset.prev || '[]');
    const f = el.dataset.fmt || 'int';
    let view = [0, pts.length];
    el.innerHTML = '';
    const head = html('<div class="xh"></div>');
    const svgWrap = html('<div style="position:relative"></div>');
    const foot = html('<div class="xs"></div>');
    el.append(head, svgWrap, foot);
    const tip = html('<div class="tip" style="display:none"></div>');
    svgWrap.append(tip);
    function render() {
      const s = pts.slice(view[0], view[1]);
      const total = s.reduce((a, p) => a + p[1], 0);
      const zoomed = view[0] > 0 || view[1] < pts.length;
      const prevTotal = zoomed ? null : prev.reduce((a, p) => a + p[1], 0);
      let delta = '';
      if (prevTotal !== null && prev.length) {
        const pct = prevTotal ? Math.round(((total - prevTotal) / prevTotal) * 100) : total ? 100 : 0;
        delta = `<span class="delta ${pct > 0 ? 'up' : pct < 0 ? 'down' : 'flat'}" title="Previous ${pts.length} days: ${fmt(prevTotal, f)}">${pct > 0 ? '↑' : pct < 0 ? '↓' : '→'} ${Math.abs(pct)}%</span>`;
      }
      head.innerHTML = `<span class="xt">${esc(el.dataset.title)}</span>${delta}<span class="xv">${fmt(total, f)}</span>`;
      const W = 600, H = 120, n = s.length || 1, bw = W / n;
      const max = Math.max(1, ...s.map((p) => p[1]), ...(zoomed ? [] : prev.map((p) => p[1])));
      const bars = s.map((p, i) => { const h = p[1] ? Math.max(2, (p[1] / max) * (H - 6)) : 0; return `<rect class="b" data-i="${i}" x="${i * bw + bw * 0.12}" y="${H - h}" width="${bw * 0.76}" height="${h}" rx="${Math.min(4, bw / 4)}"/>`; }).join('');
      const line = !zoomed && prev.length === pts.length ? `<polyline class="prev" points="${prev.map((p, i) => `${i * bw + bw / 2},${H - (p[1] / max) * (H - 6)}`).join(' ')}"/>` : '';
      $$('svg', svgWrap).forEach((x) => x.remove());
      svgWrap.insertAdjacentHTML('afterbegin', `<svg viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">${bars}${line}<rect class="sel" y="0" height="${H}" width="0" style="display:none"/></svg>`);
      foot.innerHTML = `<span>${esc(s[0]?.[0] || '')} → ${esc(s[s.length - 1]?.[0] || '')}</span>${line ? '<span>┄ previous period</span>' : ''}${zoomed ? '<a>Reset zoom</a>' : '<span style="margin-left:auto;color:var(--faint)">drag to zoom</span>'}`;
      const reset = $('a', foot); if (reset) reset.onclick = () => { view = [0, pts.length]; render(); };
      const svg = $('svg', svgWrap), sel = $('.sel', svg);
      const idxAt = (ev) => { const r = svg.getBoundingClientRect(); return Math.max(0, Math.min(n - 1, Math.floor(((ev.clientX - r.left) / r.width) * n))); };
      let dragFrom = null;
      svg.onpointermove = (ev) => {
        const i = idxAt(ev), p = s[i];
        $$('.b', svg).forEach((b) => b.classList.toggle('hl', +b.dataset.i === i));
        const r = svg.getBoundingClientRect();
        tip.style.display = 'block';
        tip.style.left = ((i + 0.5) / n) * r.width + 'px';
        tip.style.top = '0px';
        const pv = !zoomed && prev[i] ? `<br>previous: ${fmt(prev[i][1], f)}` : '';
        tip.innerHTML = `<b>${fmt(p[1], f)}</b>${esc(p[0])}${pv}`;
        if (dragFrom !== null) { const a = Math.min(dragFrom, i), b = Math.max(dragFrom, i); sel.style.display = ''; sel.setAttribute('x', a * bw); sel.setAttribute('width', (b - a + 1) * bw); }
      };
      svg.onpointerleave = () => { tip.style.display = 'none'; $$('.b', svg).forEach((b) => b.classList.remove('hl')); };
      svg.onpointerdown = (ev) => { dragFrom = idxAt(ev); svg.setPointerCapture(ev.pointerId); };
      svg.onpointerup = (ev) => {
        if (dragFrom === null) return;
        const i = idxAt(ev), a = Math.min(dragFrom, i), b = Math.max(dragFrom, i);
        dragFrom = null;
        if (b - a >= 1) { view = [view[0] + a, view[0] + b + 1]; render(); } else sel.style.display = 'none';
      };
    }
    render();
  }

  // ---------------- gallery, lightbox, waveforms ----------------
  const lb = $('.lightbox');
  let lbItems = [], lbAt = 0;
  function lbShow(i) {
    lbAt = (i + lbItems.length) % lbItems.length;
    const a = lbItems[lbAt];
    $('.lb-media', lb).innerHTML = a.dataset.kind === 'video' ? `<video src="${a.href}" controls autoplay></video>` : `<img src="${a.href}" alt="">`;
    $('.lb-cap', lb).textContent = `${a.dataset.name || ''}  ·  ${lbAt + 1} / ${lbItems.length}`;
    lb.classList.add('on');
  }
  const lbClose = () => { lb.classList.remove('on'); $('.lb-media', lb).innerHTML = ''; };
  lb.onclick = (e) => {
    if (e.target.closest('.lb-prev')) lbShow(lbAt - 1);
    else if (e.target.closest('.lb-next')) lbShow(lbAt + 1);
    else if (e.target.closest('.lb-close') || e.target === lb) lbClose();
  };
  async function drawWave(w) {
    const cv = $('canvas', w), audio = $('audio', w), btn = $('.wp', w);
    const ctx = cv.getContext('2d');
    let peaks = new Array(140).fill(0.15);
    const paint = () => {
      const dpr = devicePixelRatio || 1, W = cv.clientWidth, H = cv.clientHeight;
      cv.width = W * dpr; cv.height = H * dpr; ctx.scale(dpr, dpr);
      const played = audio.duration ? audio.currentTime / audio.duration : 0;
      const cs = getComputedStyle(document.documentElement);
      const bw = W / peaks.length;
      peaks.forEach((p, i) => {
        ctx.fillStyle = i / peaks.length < played ? cs.getPropertyValue('--primary') : cs.getPropertyValue('--border-strong');
        const h = Math.max(2, p * H);
        ctx.fillRect(i * bw + 1, (H - h) / 2, Math.max(1, bw - 2), h);
      });
    };
    paint();
    btn.onclick = () => { if (audio.paused) audio.play(); else audio.pause(); };
    audio.onplay = () => { btn.innerHTML = icon('pause'); };
    audio.onpause = () => { btn.innerHTML = icon('play'); };
    audio.ontimeupdate = paint;
    cv.onclick = (e) => { if (audio.duration) { audio.currentTime = ((e.offsetX) / cv.clientWidth) * audio.duration; audio.play(); } };
    if (+w.dataset.size > 15 * 1048576 || !window.AudioContext) return;
    try {
      const buf = await (await fetch(audio.src)).arrayBuffer();
      const data = (await new AudioContext().decodeAudioData(buf)).getChannelData(0);
      const step = Math.floor(data.length / peaks.length) || 1;
      peaks = peaks.map((_, i) => { let m = 0; for (let j = i * step; j < (i + 1) * step && j < data.length; j += 16) m = Math.max(m, Math.abs(data[j])); return m; });
      const top = Math.max(...peaks) || 1;
      peaks = peaks.map((p) => p / top);
      paint();
    } catch { /* keep the flat bars */ }
  }

  // ---------------- dashboard widgets ----------------
  function widgets() {
    const box = $('#widgets');
    if (!box) return;
    const all = () => $$('.widget', box);
    const saved = JSON.parse(box.dataset.prefs || 'null');
    if (saved && Array.isArray(saved.order)) {
      // Saved order first; cards added later (not in the saved list) follow in their usual order.
      const rank = (w) => { const i = saved.order.indexOf(w.dataset.id); return i < 0 ? saved.order.length : i; };
      all().map((w, i) => [w, rank(w), i]).sort((a, b) => a[1] - b[1] || a[2] - b[2]).forEach(([w]) => box.append(w));
      all().forEach((w) => {
        if ((saved.hidden || []).includes(w.dataset.id)) w.classList.add('off');
        if (saved.width && saved.width[w.dataset.id]) w.dataset.w = saved.width[w.dataset.id];
      });
    }
    all().forEach((w) => w.insertAdjacentHTML('afterbegin', `<div class="wbar"><button type="button" data-wa="w" title="Width">⇔</button><button type="button" data-wa="h" title="Show/hide">${icon('eye', 'sm')}</button></div>`));
    const wrap = box.parentNode;
    let drag = null;
    box.addEventListener('click', (e) => {
      const b = e.target.closest('[data-wa]'); if (!b) return;
      const w = b.closest('.widget');
      if (b.dataset.wa === 'w') w.dataset.w = w.dataset.w === '2' ? '1' : '2';
      else w.classList.toggle('off');
    });
    box.addEventListener('dragstart', (e) => { drag = e.target.closest('.widget'); e.dataTransfer.effectAllowed = 'move'; });
    box.addEventListener('dragover', (e) => {
      if (!drag) return; e.preventDefault();
      const over = e.target.closest('.widget'); all().forEach((w) => w.classList.toggle('dragover', w === over && w !== drag));
      if (over && over !== drag) { const r = over.getBoundingClientRect(); const after = e.clientY > r.top + r.height / 2 || e.clientX > r.left + r.width / 2; over[after ? 'after' : 'before'](drag); }
    });
    box.addEventListener('dragend', () => { drag = null; all().forEach((w) => w.classList.remove('dragover')); });
    const edit = (on) => { wrap.classList.toggle('editing', on); all().forEach((w) => { w.draggable = on; }); };
    $('[data-act="customize"]') && ($('[data-act="customize"]').onclick = () => edit(true));
    $('[data-act="w-cancel"]').onclick = () => location.reload();
    $('[data-act="w-reset"]').onclick = async () => { await post('/admin/me/prefs', { key: 'dashboard', value: null }); location.reload(); };
    $('[data-act="w-save"]').onclick = async () => {
      const value = { order: all().map((w) => w.dataset.id), hidden: all().filter((w) => w.classList.contains('off')).map((w) => w.dataset.id), width: Object.fromEntries(all().map((w) => [w.dataset.id, w.dataset.w || '1'])) };
      const r = await post('/admin/me/prefs', { key: 'dashboard', value });
      edit(false);
      toast(r.ok ? 'Dashboard layout saved.' : 'Could not save the layout.', { kind: r.ok ? '' : 'err' });
    };
  }

  // ---------------- support inbox ----------------
  function inbox() {
    const box = $('.inbox');
    if (!box) return;
    const thread = $('.thread', box);
    if (thread) thread.scrollTop = thread.scrollHeight;
    const ta = $('.compose textarea', box);
    $$('[data-insert]', box).forEach((s) => { s.onchange = () => { if (s.value) { ta.value = (ta.value ? ta.value + '\n' : '') + s.value; ta.focus(); s.selectedIndex = 0; } }; });
    if (ta) ta.onkeydown = (e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); ta.form.requestSubmit(); } };
    const q = $('.il .ih input', box);
    if (q) q.oninput = () => $$('.conv a', box).forEach((a) => { a.style.display = a.textContent.toLowerCase().includes(q.value.toLowerCase()) ? '' : 'none'; });
    // New messages show up without reloading.
    setInterval(async () => {
      if (document.hidden) return;
      try {
        const doc = new DOMParser().parseFromString(await (await fetch(location.href, { headers: { Accept: 'text/html' } })).text(), 'text/html');
        const nt = $('.thread', doc), nc = $('.conv', doc);
        if (thread && nt && nt.innerHTML !== thread.innerHTML) { const end = thread.scrollHeight - thread.scrollTop - thread.clientHeight < 40; thread.innerHTML = nt.innerHTML; if (end) thread.scrollTop = thread.scrollHeight; }
        if (nc && $('.conv', box) && nc.innerHTML !== $('.conv', box).innerHTML) { $('.conv', box).innerHTML = nc.innerHTML; if (q) q.oninput(); }
      } catch { /* offline for a moment */ }
    }, 8000);
  }

  // ---------------- enhance (page and side panel) ----------------
  function enhance(scope) {
    $$('table', scope).forEach((t, i) => enhanceTable(t, (scope === document ? 'm' : 'd') + i));
    $$('.xchart', scope).forEach(drawChart);
    $$('.wave', scope).forEach(drawWave);
  }

  // ---------------- side panel ----------------
  const drawer = $('.drawer');
  const drawable = (a) => a && a.closest('main, .drawer .db, .notif') && a.closest('table, .tl, .conv, .notif') &&
    /^\/admin\/(users\/\d+|shares\/[A-Za-z0-9]+)$/.test(a.getAttribute('href') || '');
  async function openDrawer(url) {
    drawer.classList.add('open'); scrim.classList.add('on');
    $('.dh b', drawer).textContent = 'Loading…';
    $('.dh a', drawer).href = url;
    const body = $('.db', drawer);
    body.innerHTML = '<div class="skeleton" style="width:40%;height:22px"></div><div class="skeleton"></div><div class="skeleton" style="width:70%"></div><div class="skeleton" style="height:120px"></div>';
    try {
      const doc = new DOMParser().parseFromString(await (await fetch(url, { headers: { Accept: 'text/html' } })).text(), 'text/html');
      const m = doc.querySelector('main');
      $$('.flash, p:first-child > a[href="/admin/users"], p:first-child > a[href="/admin/shares"]', m).forEach((x) => (x.closest('p') || x).remove());
      body.innerHTML = m ? m.innerHTML : 'Could not load this page.';
      $('.dh b', drawer).textContent = (doc.title || '').split(' · ')[0];
      body.scrollTop = 0;
      enhance(body);
    } catch { body.textContent = 'Could not load this page.'; }
  }
  const closeDrawer = () => { drawer.classList.remove('open'); if (!side.classList.contains('open')) scrim.classList.remove('on'); };

  // ---------------- command palette ----------------
  const pal = $('.palette'), pin = $('.pin input', pal), plist = $('.pl', pal);
  let pItems = [], pSel = 0, pTimer;
  function pRender(groups) {
    pItems = [];
    plist.innerHTML = groups.filter((g) => g.items.length).map((g) => `<div class="pg">${esc(g.name)}</div>` + g.items.map((it) => {
      pItems.push(it);
      return `<div class="pi" data-n="${pItems.length - 1}"><span class="ib">${icon(it.icon || 'arrow')}</span><div><div>${esc(it.label)}</div>${it.sub ? `<small>${esc(it.sub)}</small>` : ''}</div></div>`;
    }).join('')).join('') || '<div class="pg">Nothing found</div>';
    pSel = 0; pMark();
  }
  const pMark = () => $$('.pi', plist).forEach((el) => { el.classList.toggle('on', +el.dataset.n === pSel); if (+el.dataset.n === pSel) el.scrollIntoView({ block: 'nearest' }); });
  function pSearch() {
    const q = pin.value.trim().toLowerCase();
    const m = (it) => !q || (it.label + ' ' + (it.sub || '') + ' ' + (it.keys || '')).toLowerCase().includes(q);
    const groups = [{ name: 'Go to', items: A.pages.filter(m) }, { name: 'Do', items: A.actions.filter(m) }];
    pRender(groups);
    clearTimeout(pTimer);
    if (q.length >= 2) pTimer = setTimeout(async () => {
      try {
        const r = await (await fetch('/admin/search.json?q=' + encodeURIComponent(q))).json();
        if (pin.value.trim().toLowerCase() !== q) return;
        pRender([{ name: 'Results', items: r.results || [] }, ...groups]);
      } catch { /* ignore */ }
    }, 160);
  }
  function pRun(it) {
    closePalette();
    if (!it) return;
    if (it.post) {
      const f = html(`<form method="post" action="${esc(it.post)}"><input type="hidden" name="csrf" value="${esc(A.csrf)}"></form>`);
      document.body.append(f); f.dataset.ok = '1';
      return it.confirm ? ask({ title: it.confirm, danger: false, ok: it.label }).then((ok) => ok && f.submit()) : f.submit();
    }
    if (it.drawer) return openDrawer(it.href);
    location.href = it.href;
  }
  function openPalette() { pal.classList.add('on'); scrim.classList.add('on'); pin.value = ''; pSearch(); setTimeout(() => pin.focus(), 20); }
  function closePalette() { pal.classList.remove('on'); if (!drawer.classList.contains('open') && !side.classList.contains('open')) scrim.classList.remove('on'); }
  pin.oninput = pSearch;
  pin.onkeydown = (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); pSel = Math.min(pItems.length - 1, pSel + 1); pMark(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); pSel = Math.max(0, pSel - 1); pMark(); }
    else if (e.key === 'Enter') { e.preventDefault(); pRun(pItems[pSel]); }
  };
  plist.onclick = (e) => { const el = e.target.closest('.pi'); if (el) pRun(pItems[+el.dataset.n]); };
  plist.onmousemove = (e) => { const el = e.target.closest('.pi'); if (el && +el.dataset.n !== pSel) { pSel = +el.dataset.n; pMark(); } };

  // ---------------- notifications & live pulse ----------------
  const bell = $('[data-act="bell"]'), panel = $('.notif');
  let lastNotif = null;
  async function loadNotifs() {
    const r = await (await fetch('/admin/notifications.json')).json();
    $('.nl', panel).innerHTML = r.items.map((n) => `<a class="ni ${n.unread ? 'unread' : ''}" href="${esc(n.link || '#')}"><span class="nk">${icon(n.icon || 'bell', 'sm')}</span>
      <span><b>${esc(n.title)}</b><small>${esc(n.body || '')}</small><small>${ago(n.at)}</small></span></a>`).join('') || '<div class="empty">You\'re all caught up ✨</div>';
    if (r.items.some((n) => n.unread)) { await post('/admin/notifications/read', {}); setBadge('notif', 0); }
  }
  function setBadge(k, n) { $$(`[data-badge="${k}"]`).forEach((b) => { b.textContent = n > 99 ? '99+' : n || ''; b.dataset.n = n || 0; }); }
  async function pulse() {
    if (document.hidden) return;
    try {
      const r = await (await fetch('/admin/pulse.json', { cache: 'no-store' })).json();
      ['reports', 'support', 'alerts', 'notif'].forEach((k) => setBadge(k, r.badges[k] || 0));
      $$('[data-live]').forEach((el) => { el.textContent = r.live[el.dataset.live] ?? '–'; });
      if (lastNotif !== null && r.latest && r.latest.id > lastNotif) toast(r.latest.title, { kind: 'info', ms: 6000 });
      if (r.latest) lastNotif = Math.max(lastNotif || 0, r.latest.id);
    } catch { /* offline */ }
  }
  pulse(); setInterval(pulse, 15000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) pulse(); });

  // ---------------- clicks ----------------
  document.addEventListener('click', (e) => {
    if (!(e.target instanceof Element)) return;
    if (!e.target.closest('.popmenu')) $$('.popmenu').forEach((m) => m.remove());
    if (panel.classList.contains('on') && !e.target.closest('.notif, [data-act="bell"]')) panel.classList.remove('on');
    const act = e.target.closest('[data-act]')?.dataset.act;
    if (act === 'theme') setTheme(root.dataset.theme === 'dark' ? 'light' : 'dark');
    else if (act === 'mini') { root.classList.toggle('mini'); store.set('adm-mini', root.classList.contains('mini')); }
    else if (act === 'menu') { side.classList.add('open'); scrim.classList.add('on'); }
    else if (act === 'palette') openPalette();
    else if (act === 'close-drawer') closeDrawer();
    else if (act === 'bell') { panel.classList.toggle('on'); if (panel.classList.contains('on')) loadNotifs(); }
    else if (act === 'keys') showKeys();
    if (e.target === scrim) { closeDrawer(); closePalette(); closeSide(); }
    const a = e.target.closest('a');
    if (a && !e.ctrlKey && !e.metaKey && !e.shiftKey && e.button === 0 && drawable(a)) { e.preventDefault(); panel.classList.remove('on'); openDrawer(a.getAttribute('href')); }
    const g = e.target.closest('.gallery a');
    if (g && !e.ctrlKey && !e.metaKey) { e.preventDefault(); lbItems = $$('a', g.closest('.gallery')); lbShow(lbItems.indexOf(g)); }
  });

  // ---------------- keyboard ----------------
  function showKeys() {
    ask({ title: 'Keyboard shortcuts', danger: false, ok: 'Got it', text: '' }).then(() => {});
    $('p', modal).outerHTML = `<div style="display:grid;grid-template-columns:auto 1fr;gap:8px 14px;margin:6px 0 16px;font-size:13px">
      <span><kbd>Ctrl</kbd> <kbd>K</kbd></span><span>Search & jump anywhere</span><span><kbd>/</kbd></span><span>Filter the list</span>
      <span><kbd>j</kbd> <kbd>k</kbd></span><span>Next / previous row</span><span><kbd>Enter</kbd></span><span>Open the row</span>
      <span><kbd>Esc</kbd></span><span>Close panel / dialog</span>
      ${[['d', 'Dashboard'], ['l', 'Live'], ['u', 'Users'], ['s', 'Shares'], ['r', 'Reports'], ['h', 'Support'], ['p', 'Plans'], ['a', 'Alerts'], ['b', 'Backups'], ['t', 'Toggle theme']]
        .map(([k, l]) => `<span><kbd>g</kbd> <kbd>${k}</kbd></span><span>${l}</span>`).join('')}<span><kbd>?</kbd></span><span>This help</span></div>`;
  }
  let gMode = 0, cur = -1;
  const rowsNow = () => $$((drawer.classList.contains('open') ? '.drawer .db' : 'main') + ' table tr').filter((r) => r.offsetParent && r.querySelector('td') && !r.querySelector('td[colspan]'));
  document.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); return pal.classList.contains('on') ? closePalette() : openPalette(); }
    if (e.key === 'Escape') { if (lb.classList.contains('on')) return lbClose(); closePalette(); closeDrawer(); closeSide(); panel.classList.remove('on'); return; }
    if (lb.classList.contains('on')) { if (e.key === 'ArrowLeft') lbShow(lbAt - 1); if (e.key === 'ArrowRight') lbShow(lbAt + 1); return; }
    const el = e.target instanceof Element ? e.target : document.body;
    if (el.closest('input, textarea, select, [contenteditable]') || e.ctrlKey || e.metaKey || e.altKey) return;
    if (gMode && Date.now() - gMode < 1200) {
      gMode = 0;
      const go = { d: '/admin', l: '/admin/live', u: '/admin/users', s: '/admin/shares', r: '/admin/reports', h: '/admin/support', p: '/admin/plans', a: '/admin/alerts', b: '/admin/backups' }[e.key];
      if (e.key === 't') return setTheme(root.dataset.theme === 'dark' ? 'light' : 'dark');
      if (go) location.href = go;
      return;
    }
    if (e.key === 'g') { gMode = Date.now(); return; }
    if (e.key === '?') return showKeys();
    if (e.key === '/') { const f = $((drawer.classList.contains('open') ? '.drawer .db' : 'main') + ' .tbar input') || $('.search input'); if (f) { e.preventDefault(); f.focus(); } else { e.preventDefault(); openPalette(); } return; }
    if (e.key === 'j' || e.key === 'k') {
      const rows = rowsNow(); if (!rows.length) return;
      rows.forEach((r) => r.classList.remove('cur'));
      cur = Math.max(0, Math.min(rows.length - 1, cur + (e.key === 'j' ? 1 : -1)));
      rows[cur].classList.add('cur'); rows[cur].scrollIntoView({ block: 'nearest' });
    }
    if (e.key === 'Enter') { const r = $('tr.cur'); const a = r && $('a[href]', r); if (a) a.click(); }
  });

  enhance(document);
  widgets();
  inbox();
})();
