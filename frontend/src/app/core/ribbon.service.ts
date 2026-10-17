import { DestroyRef, Injectable, NgZone, effect, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SessionService } from './auth/session.service';
import { ThemeService } from './theme.service';
import { MotionPreferenceService } from './motion-preference.service';

/** One optional decorative canvas. Never receives input or owns application state. */
@Injectable({ providedIn: 'root' })
export class RibbonService {
  private readonly motion = inject(MotionPreferenceService);
  private readonly theme = inject(ThemeService);
  private readonly zone = inject(NgZone);
  private readonly destroy = inject(DestroyRef);
  private readonly fine = window.matchMedia?.('(any-hover: hover) and (any-pointer: fine)');
  private readonly forced = window.matchMedia?.('(forced-colors: active)');
  private canvas: HTMLCanvasElement | null = null;
  private ctx: CanvasRenderingContext2D | null = null;
  private frame = 0;
  private lastFrame = 0;
  private moved = 0;
  private x = 0;
  private y = 0;
  private started = false;
  private readonly points = Array.from({ length: 18 }, () => ({ x: 0, y: 0 }));
  private bloom: { x: number; y: number; at: number } | null = null;
  private sample: { element: HTMLElement; at: number } | null = null;
  private ink = '';
  private warm = '';

  constructor() {
    inject(SessionService)
      .changes.pipe(takeUntilDestroyed())
      .subscribe(() => this.clear());
    effect(() => {
      this.theme.palette();
      this.theme.effectiveDark();
      this.motion.reduced();
      this.refresh();
    });
    this.destroy.onDestroy(() => {
      this.clear();
      this.canvas?.remove();
      document.documentElement.removeAttribute('data-ribbon');
    });
  }
  init(): void {
    if (this.canvas || typeof CanvasRenderingContext2D === 'undefined') return;
    this.canvas = document.createElement('canvas');
    this.canvas.className = 'silk-ribbon';
    this.canvas.setAttribute('aria-hidden', 'true');
    this.canvas.hidden = true;
    this.ctx = this.canvas.getContext('2d');
    if (!this.ctx) return;
    document.body.append(this.canvas);
    this.zone.runOutsideAngular(() => {
      const listen = (target: EventTarget, type: string, handler: EventListener) => {
        target.addEventListener(type, handler, { passive: true });
        this.destroy.onDestroy(() => target.removeEventListener(type, handler));
      };
      listen(document, 'pointermove', ((e: PointerEvent) => {
        if (e.pointerType !== 'mouse' || !this.allowed()) return;
        if (this.editing(e.target)) return this.clear();
        this.sample = null;
        this.start(e.clientX, e.clientY);
        const glass = (e.target as Element).closest<HTMLElement>('.dc-glass');
        if (glass) {
          const r = glass.getBoundingClientRect();
          glass.style.setProperty('--shine-x', `${((e.clientX - r.x) / r.width) * 100}%`);
          glass.style.setProperty('--shine-y', `${((e.clientY - r.y) / r.height) * 100}%`);
        }
      }) as EventListener);
      const touch = (e: TouchEvent, first: boolean) => {
        if (!this.allowed() || e.touches.length !== 1 || this.editing(e.target))
          return this.clear();
        const t = e.touches[0];
        if (first) this.clear();
        this.sample = null;
        this.start(t.clientX, t.clientY);
        if (first) this.bloom = { x: t.clientX, y: t.clientY, at: performance.now() };
      };
      listen(document, 'touchstart', ((e: TouchEvent) => touch(e, true)) as EventListener);
      listen(document, 'touchmove', ((e: TouchEvent) => touch(e, false)) as EventListener);
      listen(document, 'touchend', () => {
        this.moved = performance.now() - 180;
      });
      listen(document, 'touchcancel', () => this.clear());
      listen(document.documentElement, 'pointerleave', () => this.clear());
      listen(window, 'blur', () => this.clear());
      listen(window, 'resize', () => this.resize());
      listen(document, 'visibilitychange', () => {
        if (document.hidden) this.clear();
      });
      [this.fine, this.forced].forEach((media) => {
        media?.addEventListener?.('change', this.mediaChanged);
        this.destroy.onDestroy(() => media?.removeEventListener?.('change', this.mediaChanged));
      });
      this.resize();
      this.refresh();
    });
  }
  previewTouch(element: HTMLElement): void {
    if (!this.ctx || !this.allowed()) return;
    this.zone.runOutsideAngular(() => {
      this.clear();
      const r = element.getBoundingClientRect();
      this.start(r.x + r.width / 2, r.y + r.height / 2);
      this.sample = { element, at: performance.now() };
      this.bloom = { x: this.x, y: this.y, at: performance.now() };
    });
  }
  private readonly mediaChanged = () => this.refresh();
  private allowed(): boolean {
    return !this.motion.reduced() && !this.forced?.matches && !document.hidden;
  }
  private editing(target: EventTarget | null): boolean {
    return !!(target as Element | null)?.closest('input,textarea,[contenteditable=true]');
  }
  private refresh(): void {
    if (!this.ctx) return;
    this.clear();
    const root = document.documentElement;
    const style = getComputedStyle(root);
    this.ink = style.getPropertyValue('--accent').trim();
    this.warm = style.getPropertyValue('--warm').trim();
    const feature = style.getPropertyValue('--feature').trim();
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="28" height="28" viewBox="0 0 28 28"><path d="M3 2.5 23.5 12.8l-9 2-3.4 9.2z" fill="${feature}" stroke="white" stroke-width="2" stroke-linejoin="round"/><path d="m7 6 7.3 8.3" stroke="${this.warm}" stroke-width="1.6"/></svg>`;
    root.style.setProperty(
      '--ribbon-cursor',
      `url("data:image/svg+xml,${encodeURIComponent(svg)}") 3 3`,
    );
    if (this.allowed() && this.fine?.matches) root.dataset['ribbon'] = 'enabled';
    else root.removeAttribute('data-ribbon');
  }
  private resize(): void {
    this.clear();
    if (!this.ctx || !this.canvas) return;
    const dpr = Math.min(devicePixelRatio || 1, 2);
    this.canvas.width = innerWidth * dpr;
    this.canvas.height = innerHeight * dpr;
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  private start(x: number, y: number): void {
    this.x = x;
    this.y = y;
    this.moved = performance.now();
    if (!this.started) {
      this.points.forEach((p) => {
        p.x = x;
        p.y = y;
      });
      this.started = true;
      this.lastFrame = this.moved;
    }
    this.canvas!.hidden = false;
    if (!this.frame) this.frame = requestAnimationFrame(this.tick);
  }
  private readonly tick = (now: number) => {
    this.frame = 0;
    if (!this.ctx || !this.allowed()) return this.clear();
    const ctx = this.ctx;
    const dt = Math.min((now - this.lastFrame) / 16.67 || 1, 3);
    this.lastFrame = now;
    if (this.sample) {
      const elapsed = now - this.sample.at;
      const r = this.sample.element.getBoundingClientRect();
      this.x = r.x + r.width / 2 + Math.sin(elapsed / 180) * 55;
      this.y = r.y + r.height / 2 + Math.sin(elapsed / 100) * 18;
      if (elapsed < 1100) this.moved = now;
      else this.sample = null;
    }
    const p = this.points;
    const ease = 1 - Math.pow(0.52, dt);
    p[0].x += (this.x - p[0].x) * (1 - Math.pow(0.36, dt));
    p[0].y += (this.y - p[0].y) * (1 - Math.pow(0.36, dt));
    for (let i = 1; i < p.length; i++) {
      p[i].x += (p[i - 1].x - p[i].x) * ease;
      p[i].y += (p[i - 1].y - p[i].y) * ease;
    }
    ctx.clearRect(0, 0, innerWidth, innerHeight);
    if (this.bloom) {
      const t = Math.min(1, (now - this.bloom.at) / 480);
      ctx.globalAlpha = (1 - t) * 0.7;
      ctx.strokeStyle = this.ink;
      ctx.lineWidth = 1.8;
      ctx.beginPath();
      ctx.arc(this.bloom.x, this.bloom.y, 7 + t * 24, 0, Math.PI * 2);
      ctx.stroke();
      if (t === 1) this.bloom = null;
    }
    const fade = Math.max(0, 1 - Math.max(0, now - this.moved - 180) / 420);
    ctx.globalAlpha = fade;
    ctx.lineCap = 'round';
    for (let i = p.length - 1; i > 0; i--) {
      ctx.strokeStyle = i > 9 ? this.warm : this.ink;
      ctx.lineWidth = (1 - i / p.length) * 7 + 0.5;
      ctx.beginPath();
      ctx.moveTo(p[i].x, p[i].y);
      ctx.lineTo(p[i - 1].x, p[i - 1].y);
      ctx.stroke();
    }
    ctx.globalAlpha = 1;
    if (fade > 0) this.frame = requestAnimationFrame(this.tick);
    else this.clear();
  };
  clear(): void {
    cancelAnimationFrame(this.frame);
    this.frame = 0;
    this.started = false;
    this.sample = null;
    this.bloom = null;
    this.ctx?.clearRect(0, 0, innerWidth, innerHeight);
    if (this.canvas) this.canvas.hidden = true;
  }
}
