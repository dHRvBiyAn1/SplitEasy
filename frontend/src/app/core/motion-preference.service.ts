import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class MotionPreferenceService {
  private readonly media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
  private readonly systemReduced = signal(this.media?.matches ?? false);
  readonly deviceReduced = signal(this.read());
  readonly reduced = computed(() => this.systemReduced() || this.deviceReduced());

  constructor() {
    const onChange = (event: MediaQueryListEvent) => this.systemReduced.set(event.matches);
    this.media?.addEventListener?.('change', onChange);
    inject(DestroyRef).onDestroy(() => this.media?.removeEventListener?.('change', onChange));
    effect(
      () => (document.documentElement.dataset['motion'] = this.reduced() ? 'reduced' : 'full'),
    );
  }

  setReduced(reduced: boolean): void {
    this.deviceReduced.set(reduced);
    try {
      localStorage.setItem('spliteasy.motion', reduced ? 'reduced' : 'full');
    } catch {
      /* Optional device preference. */
    }
  }

  private read(): boolean {
    try {
      return localStorage.getItem('spliteasy.motion') === 'reduced';
    } catch {
      return false;
    }
  }
}
