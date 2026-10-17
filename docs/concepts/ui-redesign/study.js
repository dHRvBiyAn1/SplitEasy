// Isolated design study. No API calls, account state or persistent financial data.
const root = document.body;
const sheet = document.querySelector('#sheet');
const sheetBody = document.querySelector('#sheet-body');
const title = document.querySelector('#sheet-title');
const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
let playback = [];
let toastTimer;
let page = 'overview';
function cancelMotion(){playback.forEach(animation=>animation.cancel());playback=[];}
function animateEntrance(){
  cancelMotion();
  if(reduced.matches||root.classList.contains('motion-reduced')) return;
  const current = document.getElementById(page);
  const heading=current.querySelector('.page-heading, .auth-story');
  if(heading) playback.push(heading.animate([{opacity:0,transform:'translateY(12px)'},{opacity:1,transform:'none'}],{duration:360,easing:'cubic-bezier(.22,1,.36,1)'}));
  const ticket=current.querySelector('.balance-ticket, .auth-ticket');
  if(ticket) playback.push(ticket.animate([{opacity:0,transform:'translateY(20px) rotate(4deg)'},{opacity:1,transform:'rotate(-1deg)'}],{duration:640,easing:'cubic-bezier(.22,1,.36,1)'}));
  current.querySelectorAll('.group-pass').forEach((card,i)=>playback.push(card.animate([{opacity:0,transform:`translateY(${18+i*4}px) rotate(${i-1}deg)`},{opacity:1,transform:'none'}],{duration:400,delay:80+i*40,easing:'cubic-bezier(.22,1,.36,1)',fill:'backwards'})));
}
function navigate(next){
  page=next;document.querySelectorAll('.page').forEach(el=>el.hidden=el.id!==next);
  document.querySelectorAll('.nav nav button').forEach(el=>el.classList.toggle('selected',el.dataset.page===next));
  document.querySelector('#content').focus({preventScroll:true}); window.scrollTo({top:0,behavior:'instant'});animateEntrance();
}
function showToast(message){const toast=document.querySelector('.toast');clearTimeout(toastTimer);toast.textContent=message;toast.hidden=false;toastTimer=setTimeout(()=>toast.hidden=true,4000);}
function openSheet(kind){
  const templates={
    expense:['Add expense',`<form id="expense-demo"><div class="expense-form"><div class="form-fields"><label>Group<select><option>Lisbon long weekend</option><option>Our place on Willow Street</option><option>Sunday supper club</option></select></label><label>Description<input value="Dinner at Ramiro" required maxlength="200" /></label><div class="form-row"><label>Amount ($)<input type="number" inputmode="decimal" min="0.01" step="0.01" value="186.30" required /></label><label>Date<input type="date" value="2026-10-15" required /></label></div><div class="form-row"><label>Paid by<select><option>You</option><option>Noor Kapoor</option><option>Luca Fernández-Rossi</option></select></label><label>Category<select><option>Food & drink</option><option>Groceries</option><option>Rent & home</option><option>Utilities</option><option>Travel</option><option>Transport</option><option>Fun</option><option>Other</option></select></label></div></div><div class="split-preview"><h3>Share it fairly.</h3><div class="segmented" role="group" aria-label="Split type"><button type="button" class="active" data-split="equal" aria-pressed="true">Equal</button><button type="button" data-split="exact" aria-pressed="false">Exact</button><button type="button" data-split="percent" aria-pressed="false">Percent</button></div><div id="split-rows"></div><p class="fine" id="split-summary">3 people · $62.10 each</p></div></div><div class="sheet-actions"><button type="button" class="button secondary" data-close>Cancel</button><button class="button primary">Preview save</button></div><p class="fine">Composition demo. Split controls illustrate the layout; production validation is specified in the written plan.</p></form>`],
    settle:['Settle up',`<p class="muted">Lisbon long weekend</p><p class="fine" style="margin:12px 0 20px">Direct balances · follows this group’s setting</p><div class="settlement-list"><button class="settlement-row" data-open="confirm"><span class="avatar peach">LF</span><span>Luca owes you<p>Lisbon long weekend</p></span><strong class="positive">$147.50 ↗</strong></button><button class="settlement-row" data-open="confirm-noor"><span class="avatar lilac">NK</span><span>Noor owes you<p>Lisbon long weekend</p></span><strong class="positive">$121.10 ↗</strong></button></div><p class="fine">Recording a payment updates the ledger. It does not send money.</p>`],
    confirm:['Record payment',`<form id="payment-demo" class="settlement-receipt"><span class="avatar peach">LF</span><h3>Luca is paying you</h3><label>Amount to record ($)<input type="number" value="147.50" min="0.01" max="10000000000" step="0.01" required inputmode="decimal" /></label><p>Lisbon long weekend · cash settlement</p><p class="fine" style="margin:14px auto">Record the amount actually paid. Partial payments are welcome.</p><div class="sheet-actions"><button type="button" class="button secondary" data-open="settle">Back</button><button class="button primary">Preview recorded payment</button></div></form>`],
    'new-group':['Start something together.',`<form id="group-demo" class="form-fields"><label>Group name<input placeholder="e.g. Sunday supper club" required maxlength="100" /></label><label>Type<select><option>Home</option><option>Trip</option><option>Dining</option><option>Event</option><option>Other</option></select></label><label>Member email (optional)<input type="email" placeholder="friend@example.com" /></label><p class="fine">Members need an existing SplitEasy account.</p><div class="sheet-actions"><button type="button" class="button secondary" data-close>Cancel</button><button class="button primary">Preview group creation</button></div></form>`],
    member:['Add member',`<form id="member-demo" class="form-fields"><p>To Lisbon long weekend</p><label>Existing member’s email<input type="email" placeholder="friend@example.com" required /></label><p class="fine">They need an existing SplitEasy account. No invitation email is sent.</p><div class="sheet-actions"><button type="button" class="button secondary" data-close>Cancel</button><button class="button primary">Preview added member</button></div></form>`],
    settings:['Group settings',`<div class="form-fields"><label>Group name<input value="Lisbon long weekend" /></label><label>Simplify debts<select><option>Off · direct balances</option><option>On · suggested transfers</option></select></label><p class="fine">This setting applies to the group. Members with unsettled balances cannot leave or be removed.</p><button class="button secondary" data-open="member">Manage members</button><button class="text-button negative" data-open="delete">Delete group…</button></div>`],
    delete:['Delete this item?',`<p>This action permanently removes its ledger records. The production app requires an explicit confirmation.</p><div class="sheet-actions"><button class="button secondary" data-close>Keep it</button><button class="button primary" data-notice="This is a design preview. Nothing was deleted.">Preview confirmation</button></div>`],
    empty:['A table worth sharing.',`<div class="settlement-receipt"><div class="split-art mini" style="margin:auto" aria-hidden="true"><span></span><span></span><i>÷</i></div><h3>Sunday supper club</h3><p>No expenses yet. Add the first dinner.</p><div class="sheet-actions"><button class="button primary" data-open="expense">Add expense</button></div></div>`],
    'group-note':['Our place on Willow Street',`<p>You owe $845.80 in this group.</p><p class="fine">This study gives Lisbon the full group-detail composition. Every real group would use the same component with its own data.</p><div class="sheet-actions"><button class="button primary" data-close>Got it</button></div>`],
  };
  if(kind==='confirm-noor') {openSheet('confirm');sheetBody.querySelector('h3').textContent='Noor is paying you';sheetBody.querySelector('.avatar').textContent='NK';sheetBody.querySelector('input').value='121.10';return;}
  const content=templates[kind];if(!content)return;
  title.textContent=content[0];sheetBody.innerHTML=content[1];
  if(kind==='expense')renderSplit('equal');
  if(!sheet.open)sheet.showModal();
  const target=sheetBody.querySelector('input,button,select');if(target)target.focus({preventScroll:true});
  if(!reduced.matches&&!root.classList.contains('motion-reduced'))playback.push(sheet.animate([{opacity:0,transform:'translateY(14px) scale(.98)'},{opacity:1,transform:'none'}],{duration:260,easing:'cubic-bezier(.22,1,.36,1)'}));
}
function renderSplit(type){
  const names=['Ada Morgan','Noor Kapoor','Luca Fernández-Rossi'];
  document.querySelector('#split-rows').innerHTML=names.map((name,i)=>`<div class="split-person"><span class="avatar">${['AM','NK','LF'][i]}</span><span>${i===0?'You':name.split(' ')[0]}</span>${type==='equal'?'<strong>$62.10</strong>':`<input type="number" aria-label="${name}’s ${type==='percent'?'percentage':'share in dollars'}" value="${type==='percent'?(i===0?'50':'25'):'62.10'}" min="0" step="0.01" />`}</div>`).join('');
  document.querySelector('#split-summary').textContent=type==='equal'?'3 people · $62.10 each':type==='percent'?'100.00% assigned · amounts settle to the cent':'$186.30 assigned of $186.30';
  document.querySelectorAll('[data-split]').forEach(el=>{el.classList.toggle('active',el.dataset.split===type);el.setAttribute('aria-pressed',String(el.dataset.split===type));});
}
document.addEventListener('click',event=>{
  const target=event.target.closest('button,a');if(!target)return;
  if(target.dataset.page){event.preventDefault();navigate(target.dataset.page);}
  if(target.dataset.open)openSheet(target.dataset.open);
  if(target.hasAttribute('data-close'))sheet.close();
  if(target.dataset.split)renderSplit(target.dataset.split);
  if(target.dataset.notice){sheet.close();showToast(target.dataset.notice);}
});
document.querySelector('#close-sheet').addEventListener('click',()=>sheet.close());
sheet.addEventListener('close',()=>{cancelMotion();});
document.addEventListener('submit',event=>{
  event.preventDefault();
  if(event.target.id==='payment-demo'){
    title.textContent='Payment recorded';sheetBody.innerHTML='<div class="settlement-receipt"><div class="success-mark" aria-hidden="true">✓</div><h3>A little more square.</h3><p>Sample receipt · no ledger was changed.</p><div class="sheet-actions"><button class="button primary" data-close>Done</button></div></div>';
    sheetBody.querySelector('button').focus();
    if(!reduced.matches&&!root.classList.contains('motion-reduced'))playback.push(document.querySelector('.success-mark').animate([{opacity:0,transform:'rotate(-20deg) scale(1.2)'},{opacity:1,transform:'rotate(-12deg) scale(1)'}],{duration:380,easing:'cubic-bezier(.22,1,.36,1)'}));
  } else if(event.target.id==='mock-login'){event.target.reset();navigate('overview');}
  else {sheet.close();showToast('Preview complete. No data was saved.');}
});
function toggleTheme(){window.studyOptions?.toggleTheme();}
function toggleMotion(){root.classList.toggle('motion-reduced');document.querySelector('#motion').setAttribute('aria-pressed',String(root.classList.contains('motion-reduced')));cancelMotion();}
document.querySelector('#profile-theme').addEventListener('click',toggleTheme);
document.querySelector('#motion').addEventListener('click',toggleMotion);document.querySelector('#profile-motion').addEventListener('click',toggleMotion);
document.querySelector('#direction').addEventListener('change',event=>{if(event.target.value!=='club')window.location.href=event.target.value+'.html';});
document.querySelector('#replay').addEventListener('click',animateEntrance);reduced.addEventListener('change',()=>{cancelMotion();});
document.fonts.ready.then(animateEntrance);
