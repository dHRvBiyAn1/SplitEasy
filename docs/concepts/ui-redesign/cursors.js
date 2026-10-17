// Original cursor study. Keep a precise native custom SVG tip; animate only its companion.
const follower=document.querySelector('#cursor-follower');
const reduction=matchMedia('(prefers-reduced-motion: reduce)');
const fine=matchMedia('(hover: hover) and (pointer: fine)');
const forced=matchMedia('(forced-colors: active)');
let frame=0,targetX=0,targetY=0,currentX=0,currentY=0,activeStage=null,ghostAnimations=[];
function motionAllowed(){return !reduction.matches&&!forced.matches&&!document.body.classList.contains('motion-reduced');}
function stopFollower(){if(frame)cancelAnimationFrame(frame);frame=0;follower.hidden=true;follower.classList.remove('is-target','is-pressed');activeStage?.classList.remove('is-live');activeStage=null;}
function tick(){if(!activeStage||!motionAllowed())return stopFollower();const dx=targetX-currentX,dy=targetY-currentY;currentX+=dx*.25;currentY+=dy*.25;follower.style.translate=`${currentX}px ${currentY}px`;if(Math.abs(dx)+Math.abs(dy)>.3)frame=requestAnimationFrame(tick);else frame=0;}
document.querySelectorAll('.cursor-stage').forEach(stage=>{
  stage.addEventListener('pointerenter',event=>{if(!fine.matches||event.pointerType!=='mouse'||!motionAllowed())return;activeStage=stage;stage.classList.add('is-live');follower.dataset.kind=stage.dataset.cursor;currentX=targetX=event.clientX;currentY=targetY=event.clientY;follower.style.translate=`${currentX}px ${currentY}px`;follower.hidden=false;});
  stage.addEventListener('pointermove',event=>{if(activeStage!==stage)return;targetX=event.clientX;targetY=event.clientY;const editing=event.target.closest('input,textarea,[contenteditable]');follower.hidden=Boolean(editing);follower.classList.toggle('is-target',Boolean(event.target.closest('button,a')));if(!frame)frame=requestAnimationFrame(tick);},{passive:true});
  stage.addEventListener('pointerleave',stopFollower);stage.addEventListener('pointercancel',stopFollower);
  stage.addEventListener('pointerdown',()=>follower.classList.add('is-pressed'));
  stage.addEventListener('pointerup',()=>follower.classList.remove('is-pressed'));
  stage.querySelector('button').addEventListener('click',()=>{if(!motionAllowed())return;const shapes=follower.querySelectorAll('i');if(stage.dataset.cursor==='orbit')ghostAnimations.push(shapes[1].animate([{transform:'rotate(0deg) translateY(-17px)'},{transform:'rotate(180deg) translateY(-17px)'}],{duration:360,easing:'ease-out'}));else ghostAnimations.push(shapes[0].animate([{scale:'.85'},{scale:'1'}],{duration:180,easing:'cubic-bezier(.22,1,.36,1)'}));});
});
function stopSamples(){ghostAnimations.forEach(a=>a.cancel());ghostAnimations=[];document.querySelectorAll('.is-rehearsing').forEach(el=>el.classList.remove('is-rehearsing'));}
document.querySelectorAll('[data-rehearse]').forEach(button=>button.addEventListener('click',()=>{stopSamples();const stage=document.querySelector(`[data-cursor="${button.dataset.rehearse}"]`);if(!motionAllowed())return;stage.classList.add('is-rehearsing');const ghost=stage.querySelector('.cursor-ghost');const animation=ghost.animate([{translate:'0px 0px',rotate:'0deg'},{translate:'-38px 26px',rotate:'-10deg',offset:.45},{translate:'-38px 26px',rotate:'0deg',offset:.65},{translate:'0px 0px',rotate:'0deg'}],{duration:1500,easing:'cubic-bezier(.22,1,.36,1)'});ghostAnimations.push(animation);animation.finished.then(()=>stage.classList.remove('is-rehearsing')).catch(()=>{});}));
document.querySelector('#cursor-reduce').addEventListener('click',event=>{document.body.classList.toggle('motion-reduced');event.currentTarget.setAttribute('aria-pressed',String(document.body.classList.contains('motion-reduced')));stopFollower();stopSamples();});
window.addEventListener('blur',()=>{stopFollower();stopSamples();});document.addEventListener('visibilitychange',()=>{if(document.hidden){stopFollower();stopSamples();}});[reduction,fine,forced].forEach(media=>media.addEventListener('change',()=>{stopFollower();stopSamples();}));window.addEventListener('pagehide',()=>{stopFollower();stopSamples();});
