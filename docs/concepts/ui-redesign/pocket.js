// Selected Pocket Objects study: appearance, navigation and restrained glass feedback.
(() => {
  const body = document.body;
  const dialog = document.querySelector('.alt-dialog');
  const palettes = [...window.studyPalettes.objects].sort((a,b) => (a.id==='seafoam'?-1:b.id==='seafoam'?1:0));
  const sun = '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>';
  const moon = '<path d="M20.5 14A8.5 8.5 0 0 1 10 3.5 8.5 8.5 0 1 0 20.5 14Z"/>';
  const icon = paths => `<svg viewBox="0 0 24 24" aria-hidden="true">${paths}</svg>`;
  let destination = location.hash === '#groups' ? 'groups' : 'overview';
  let dialogDestination = null;
  function activeNav(next) {
    body.dataset.nav = next;
    document.querySelectorAll('[data-pocket-nav]').forEach(el => {
      const active = el.dataset.pocketNav === next;
      el.dataset.active = String(active);
      if (active) el.setAttribute('aria-current','page'); else el.removeAttribute('aria-current');
    });
  }
  function syncAppearance() {
    document.querySelectorAll('[data-pocket-palette]').forEach(button => button.setAttribute('aria-pressed',String(button.dataset.pocketPalette===window.studyOptions.palette)));
    document.querySelectorAll('[data-pocket-mode]').forEach(button => button.setAttribute('aria-pressed',String(button.dataset.pocketMode===window.studyOptions.mode)));
    document.querySelectorAll('[data-reduce]').forEach(button => button.setAttribute('aria-pressed',String(body.classList.contains('motion-reduced'))));
    const preview = document.querySelector('[data-pocket-touch-preview]');
    if (preview) preview.disabled = body.classList.contains('motion-reduced') || matchMedia('(prefers-reduced-motion: reduce)').matches;
  }
  function decorateProfile() {
    const form = dialog.querySelector('.alt-form');
    if (!form || form.querySelector('.pocket-preferences')) return;
    form.querySelector('[data-theme-preference]')?.closest('label').remove();
    const section = document.createElement('section');
    section.className='pocket-preferences';
    section.innerHTML=`<fieldset><legend>Color theme</legend><p>Choose your colors. Keep your preferred light or dark mode.</p><div class="pocket-theme-grid">${palettes.map(p=>`<button type="button" class="pocket-theme" data-pocket-palette="${p.id}" aria-pressed="false"><span class="pocket-theme-swatches" aria-hidden="true">${p.swatches.map(c=>`<i style="--swatch:${c}"></i>`).join('')}</span><span>${p.name}</span><span class="theme-check" aria-hidden="true">✓</span></button>`).join('')}</div></fieldset><fieldset><legend>Display mode</legend><div class="pocket-mode-grid"><button type="button" data-pocket-mode="light" aria-pressed="false">${icon(sun)}Light</button><button type="button" data-pocket-mode="dark" aria-pressed="false">${icon(moon)}Dark</button></div></fieldset><fieldset><legend>Silk Ribbon</legend><p>A fine ribbon for your mouse. A soft bloom and short trail for your touch.</p><div class="pocket-touch-pad"><svg class="touch-sample" viewBox="0 0 220 70" aria-hidden="true"><path d="M25 46c24-47 37 31 68-8s39 16 63-11"/><circle cx="163" cy="26" r="9"/></svg><span>Tap or trace here</span><button type="button" data-pocket-touch-preview>Preview touch</button></div><button type="button" class="pocket-motion" data-reduce aria-pressed="false">Reduce motion <span aria-hidden="true"></span></button></fieldset><p class="pocket-pref-note">Appearance is remembered in this browser for the demo.</p>`;
    form.insertBefore(section,form.querySelector('[data-demo-notice]'));
    syncAppearance();
  }
  window.pocketStudy = { decorateProfile };
  document.addEventListener('click',event=>{
    const button=event.target.closest('button,a');
    if (!button) return;
    if (button.hasAttribute('data-pocket-appearance')) {
      openDemo('profile');
      document.querySelector('#alt-dialog-title').textContent='Make it yours.';
      dialog.querySelector('.alt-form').classList.add('pocket-appearance-only');
    }
    if (button.dataset.pocketPalette) window.studyOptions.setPalette(button.dataset.pocketPalette);
    if (button.dataset.pocketMode) window.studyOptions.setMode(button.dataset.pocketMode);
    if (button.hasAttribute('data-pocket-touch-preview')) window.studyOptions.previewTouch(document.querySelector('.pocket-touch-pad'));
    if (button.dataset.pocketNav && ['overview','groups'].includes(button.dataset.pocketNav)) {
      destination=button.dataset.pocketNav; activeNav(destination);
    }
  });
  window.addEventListener('hashchange',()=>{destination=location.hash==='#groups'?'groups':'overview';if(!dialog.open)activeNav(destination);});
  new MutationObserver(()=>{
    if (dialog.open) {
      dialogDestination=dialog.querySelector('form[data-form="expense"]')?'expense':dialog.querySelector('.pocket-preferences')?'profile':null;
      activeNav(dialogDestination || destination);
    } else { dialogDestination=null;activeNav(destination); }
  }).observe(dialog,{attributes:true,attributeFilter:['open']});
  document.addEventListener('study-appearance-change',syncAppearance);
  new MutationObserver(syncAppearance).observe(body,{attributes:true,attributeFilter:['class']});
  matchMedia('(prefers-reduced-motion: reduce)').addEventListener('change',syncAppearance);
  document.querySelectorAll('.objects-nav,.object-group,.friend-object').forEach(el=>{
    el.classList.add('pocket-glass');
    el.addEventListener('pointermove',event=>{
      if(event.pointerType!=='mouse'||!matchMedia('(hover:hover)').matches)return;
      const box=el.getBoundingClientRect();
      el.style.setProperty('--shine-x',`${(event.clientX-box.x)/box.width*100}%`);
      el.style.setProperty('--shine-y',`${(event.clientY-box.y)/box.height*100}%`);
    },{passive:true});
  });
  activeNav(destination);
})();
