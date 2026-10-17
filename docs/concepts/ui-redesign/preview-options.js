// Shared, dependency-free controls for the four static design studies.
(() => {
  const body = document.body;
  const designs = window.studyPalettes;
  const design = body.dataset.direction === 'club' ? 'club' : Object.keys(designs).find(key => body.classList.contains(`${key}-design`));
  if (!design) {
    document.querySelectorAll('[data-palettes]').forEach(container => {
      const key = container.dataset.palettes;
      const file = key === 'club' ? 'index' : key;
      container.innerHTML = designs[key].map(p => `<a href="${file}.html?palette=${p.id}"><span class="palette-swatches" aria-hidden="true">${p.swatches.map(c => `<i style="--swatch:${c}"></i>`).join('')}</span>${p.name}</a>`).join('');
    });
    return;
  }
  const params = new URLSearchParams(location.search);
  const palettes = designs[design];
  const stored = key => { try { return design === 'objects' ? localStorage.getItem(`spliteasy.study.objects.${key}`) : sessionStorage.getItem(`spliteasy.study.${key}`); } catch { return null; } };
  const remember = (key,value) => { try { if (design === 'objects') localStorage.setItem(`spliteasy.study.objects.${key}`,value); else sessionStorage.setItem(`spliteasy.study.${key}`,value); } catch {} };
  let palette = palettes.find(p => p.id === params.get('palette')) || palettes.find(p => p.id === stored('palette')) || palettes.find(p => p.id === body.dataset.defaultPalette) || palettes[0];
  const requestedMode = params.get('mode') || stored('mode');
  let mode = ['light', 'dark'].includes(requestedMode) ? requestedMode : design === 'atlas' || (design === 'objects' && matchMedia('(prefers-color-scheme: dark)').matches) ? 'dark' : 'light';
  const requestedCursor = params.get('cursor') || stored('cursor');
  let cursor = design === 'objects' ? 'ribbon' : ['ribbon', 'comet', 'echo', 'native'].includes(requestedCursor) ? requestedCursor : 'ribbon';
  const bar = document.querySelector('.study-tools,.concept-bar');
  const oldTheme = document.querySelector('#theme');
  oldTheme?.remove();
  const controls = document.createElement('div');
  controls.className = 'preview-controls';
  controls.innerHTML = `<label>Palette <select aria-label="Color palette">${palettes.map(p => `<option value="${p.id}">${p.name}</option>`).join('')}</select></label><button id="theme" data-theme-toggle>Dark mode</button><label>Pointer <select aria-label="Mouse pointer"><option value="ribbon">Silk Ribbon</option><option value="comet">Comet Trail</option><option value="echo">Echo Rings</option><option value="native">System pointer</option></select></label><button data-trail-preview>Preview trail</button><span class="preview-hint" role="status"></span>`;
  bar.append(controls);
  const paletteSelect = controls.querySelector('[aria-label="Color palette"]');
  const cursorSelect = controls.querySelector('[aria-label="Mouse pointer"]');
  const hint = controls.querySelector('.preview-hint');
  paletteSelect.value = palette.id;
  cursorSelect.value = cursor;
  const roles = ['bg','surface','ink','muted','line','accent','on-accent','tint'];
  function updateUrl() {
    const url = new URL(location.href);
    url.searchParams.set('palette', palette.id);
    url.searchParams.set('mode', mode);
    url.searchParams.set('cursor', cursor);
    history.replaceState(null, '', url);
    remember('mode',mode); remember('cursor',cursor); remember('palette',palette.id);
  }
  function applyPalette() {
    body.dataset.mode = mode;
    body.dataset.palette = palette.id;
    paletteSelect.value = palette.id;
    roles.forEach((key, i) => body.style.setProperty(`--${key}`, palette[mode][i]));
    const values = {
      feature: palette.feature, 'on-feature': '#ffffff', warm: palette.warm, 'warm-ink': '#302b25',
      poster: palette.poster || palette.warm, receipt: palette.receipt || palette.warm, 'poster-ink': '#29262a',
      positive: mode === 'dark' ? '#8be0be' : '#17614c', negative: mode === 'dark' ? '#ffa8bb' : '#9b2948',
      focus: palette[mode][5],
      art1: `color-mix(in srgb, ${palette.feature} 20%, #fff9f0)`, 'art1-ink': `color-mix(in srgb, ${palette.feature} 90%, #17243b)`,
      art2: `color-mix(in srgb, ${palette.feature} 12%, #d8e9df)`, 'art2-ink': '#294e46',
      art3: `color-mix(in srgb, ${palette.warm} 65%, #fff9f0)`, 'art3-ink': '#6e443b'
    };
    const arrow = `<svg xmlns="http://www.w3.org/2000/svg" width="28" height="28" viewBox="0 0 28 28"><path d="M3 2.5 23.5 12.8l-9 2-3.4 9.2z" fill="${palette.feature}" stroke="white" stroke-width="2" stroke-linejoin="round"/><path d="m7 6 7.3 8.3" stroke="${palette.warm}" stroke-width="1.6"/></svg>`;
    body.style.setProperty('--study-cursor', `url("data:image/svg+xml,${encodeURIComponent(arrow)}") 3 3`);
    Object.entries(values).forEach(([key, value]) => body.style.setProperty(`--${key}`, value));
    // Social Club's original tokens are defined directly on body in dark mode.
    ['ground','paper','brand','on-brand','soft','butter'].forEach((key,i) => body.style.setProperty(`--${key}`, `var(--${['bg','surface','accent','on-accent','tint','warm'][i]})`));
    controls.querySelector('[data-theme-toggle]').textContent = mode === 'dark' ? 'Light mode' : 'Dark mode';
    controls.querySelector('[data-theme-toggle]').dataset.active = String(mode === 'dark');
    updateUrl();
    document.dispatchEvent(new CustomEvent('study-appearance-change'));
  }
  function toggleTheme() { mode = mode === 'dark' ? 'light' : 'dark'; applyPalette(); }
  window.studyOptions = { toggleTheme, get palette() { return palette.id; }, get mode() { return mode; },
    setPalette(id) { const next = palettes.find(p => p.id === id); if (next) { palette = next; applyPalette(); } },
    setMode(next) { if (['light','dark'].includes(next)) { mode = next; applyPalette(); } }
  };
  document.addEventListener('change', event => {
    if (!event.target.matches('[data-theme-preference]')) return;
    mode = event.target.value === 'system' ? matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light' : event.target.value;
    applyPalette();
  });
  controls.querySelector('[data-theme-toggle]').addEventListener('click', toggleTheme);
  paletteSelect.addEventListener('change', () => { palette = palettes.find(p => p.id === paletteSelect.value); applyPalette(); });
  applyPalette();

  const canvas = document.createElement('canvas');
  canvas.id = 'study-trail';
  canvas.setAttribute('aria-hidden', 'true');
  canvas.setAttribute('popover', 'manual');
  body.append(canvas);
  const ctx = canvas.getContext('2d');
  const fine = matchMedia('(any-hover: hover) and (any-pointer: fine)');
  const reduce = matchMedia('(prefers-reduced-motion: reduce)');
  const forced = matchMedia('(forced-colors: active)');
  const points = Array.from({ length: 18 }, () => ({ x: 0, y: 0 }));
  let frame = 0, target = { x:0, y:0 }, lastMove = 0, lastFrame = 0, started = false, rehearsal = 0;
  const touchEnabled = design === 'objects';
  let sampleAnchor = controls.querySelector('[data-trail-preview]'), bloom = null;
  function resize() {
    const dpr = Math.min(devicePixelRatio || 1, 2);
    canvas.width = innerWidth * dpr;
    canvas.height = innerHeight * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  function allowed() { return cursor !== 'native' && (fine.matches || touchEnabled) && !reduce.matches && !forced.matches && !body.classList.contains('motion-reduced'); }
  function clear() {
    cancelAnimationFrame(frame); frame = 0; started = false; rehearsal = 0; bloom = null;
    ctx.clearRect(0, 0, innerWidth, innerHeight);
    if (canvas.matches(':popover-open')) canvas.hidePopover();
  }
  function syncCursor() {
    clear(); body.classList.toggle('trail-enabled', allowed() && fine.matches);
    hint.textContent = reduce.matches || body.classList.contains('motion-reduced') ? 'Reduced motion · system pointer' : !fine.matches ? touchEnabled ? 'Touch to trace · lift to fade' : 'Trail uses a mouse · touch stays native' : cursor === 'native' ? 'System pointer selected' : 'Move your mouse to see the trail';
    controls.querySelector('[data-trail-preview]').disabled = !allowed();
  }
  function tick(now) {
    frame = 0;
    if (!allowed()) return clear();
    const dt = Math.min((now - lastFrame) / 16.67 || 1, 3); lastFrame = now;
    if (rehearsal) {
      const elapsed = now - rehearsal;
      const box = sampleAnchor.getBoundingClientRect();
      target = { x: Math.max(70,Math.min(innerWidth-70,box.x+box.width/2)) + Math.sin(elapsed/220)*60, y: Math.min(innerHeight-70,box.y+box.height/2) + Math.sin(elapsed/115)*24 };
      if (elapsed < 1400) lastMove = now; else rehearsal = 0;
    }
    points[0].x += (target.x - points[0].x) * (1-Math.pow(.36,dt));
    points[0].y += (target.y - points[0].y) * (1-Math.pow(.36,dt));
    for (let i=1; i<points.length; i++) {
      const ease = 1-Math.pow(.52,dt);
      points[i].x += (points[i-1].x-points[i].x)*ease;
      points[i].y += (points[i-1].y-points[i].y)*ease;
    }
    ctx.clearRect(0,0,innerWidth,innerHeight);
    const fade = Math.max(0, 1-Math.max(0,now-lastMove-180)/420);
    if (bloom) {
      const progress = Math.min(1,(now-bloom.time)/480);
      ctx.globalAlpha = (1-progress)*.7; ctx.strokeStyle=palette[mode][5]; ctx.lineWidth=1.8;
      ctx.beginPath(); ctx.arc(bloom.x,bloom.y,7+progress*24,0,Math.PI*2); ctx.stroke();
      if (progress===1) bloom=null;
    }
    ctx.globalAlpha = fade;
    const color = palette[mode][5];
    ctx.lineCap = 'round';
    if (cursor === 'ribbon') {
      for (let i=points.length-1; i>0; i--) {
        ctx.strokeStyle = i > 9 ? palette.warm : color;
        ctx.lineWidth = (1-i/points.length)*7+.5;
        ctx.beginPath(); ctx.moveTo(points[i].x,points[i].y); ctx.lineTo(points[i-1].x,points[i-1].y); ctx.stroke();
      }
    } else if (cursor === 'comet') {
      for (let i=points.length-1; i>=0; i-=2) {
        ctx.fillStyle = i > 9 ? palette.warm : color;
        ctx.globalAlpha = fade*(1-i/points.length);
        ctx.beginPath(); ctx.arc(points[i].x,points[i].y,1+(1-i/points.length)*4,0,Math.PI*2); ctx.fill();
      }
    } else {
      [3,8,13].forEach((i,n) => {
        ctx.strokeStyle = n===2 ? palette.warm : color;
        ctx.globalAlpha = fade*(.85-n*.2); ctx.lineWidth=1.5;
        ctx.beginPath(); ctx.arc(points[i].x,points[i].y,7+n*4,0,Math.PI*2); ctx.stroke();
      });
    }
    ctx.globalAlpha = 1;
    if (fade > 0) frame=requestAnimationFrame(tick); else clear();
  }
  function start(x,y) {
    target = { x,y }; lastMove = performance.now();
    if (!started) { points.forEach(p=>{p.x=x;p.y=y;}); started=true; lastFrame=lastMove; }
    if (!canvas.matches(':popover-open')) canvas.showPopover();
    if (!frame) frame=requestAnimationFrame(tick);
  }
  document.addEventListener('pointermove', event => {
    if (event.pointerType !== 'mouse' || !allowed()) return;
    if (event.target.closest('input,textarea,[contenteditable=true]')) return clear();
    rehearsal=0; canvas.dataset.input='mouse'; start(event.clientX,event.clientY);
  }, { passive:true });
  function previewTrail(anchor, asTouch = false) {
    if (!allowed()) return;
    clear();
    sampleAnchor = anchor;
    const box=anchor.getBoundingClientRect();
    const x=box.x+box.width/2,y=Math.min(innerHeight-70,box.y+box.height/2);
    canvas.dataset.input=asTouch?'touch':'mouse';
    start(x,y); rehearsal=performance.now();
    if (asTouch) bloom={x,y,time:performance.now()};
  }
  window.studyOptions.previewTouch = anchor => previewTrail(anchor,true);
  if (touchEnabled) {
    document.addEventListener('touchstart',event=>{
      if (!allowed() || event.touches.length!==1 || event.target.closest('input,textarea,[contenteditable=true]')) return clear();
      const touch=event.touches[0]; clear(); canvas.dataset.input='touch';
      start(touch.clientX,touch.clientY); bloom={x:touch.clientX,y:touch.clientY,time:performance.now()};
    },{passive:true});
    document.addEventListener('touchmove',event=>{
      if (!allowed() || event.touches.length!==1 || event.target.closest('input,textarea,[contenteditable=true]')) return clear();
      const touch=event.touches[0]; rehearsal=0; canvas.dataset.input='touch'; start(touch.clientX,touch.clientY);
    },{passive:true});
    document.addEventListener('touchend',()=>{if(canvas.dataset.input==='touch')lastMove=performance.now()-180;},{passive:true});
    document.addEventListener('touchcancel',clear,{passive:true});
  }
  controls.querySelector('[data-trail-preview]').addEventListener('click', () => {
    if (!allowed()) return;
    previewTrail(controls.querySelector('[data-trail-preview]'));
  });
  cursorSelect.addEventListener('change', () => { cursor=design==='objects'?'ribbon':cursorSelect.value; syncCursor(); updateUrl(); });
  new MutationObserver(syncCursor).observe(body,{attributes:true,attributeFilter:['class']});
  document.querySelectorAll('dialog').forEach(dialog => new MutationObserver(() => {
    if (canvas.matches(':popover-open')) { canvas.hidePopover(); canvas.showPopover(); }
  }).observe(dialog,{attributes:true,attributeFilter:['open']}));
  [fine,reduce,forced].forEach(media=>media.addEventListener('change',syncCursor));
  window.addEventListener('resize',()=>{clear();resize();});
  window.addEventListener('blur',clear);
  document.documentElement.addEventListener('pointerleave',clear);
  document.addEventListener('visibilitychange',()=>{if(document.hidden)clear();});
  window.addEventListener('pagehide',clear);
  resize(); syncCursor();
})();
