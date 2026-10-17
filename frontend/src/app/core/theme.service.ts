import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';

export type ThemeChoice = 'system' | 'light' | 'dark';
export type PaletteChoice = 'seafoam' | 'lilac' | 'peach';
export const PALETTES: { value: PaletteChoice; label: string }[] = [
  { value: 'seafoam', label: 'Seafoam & Petrol' },
  { value: 'lilac', label: 'Lilac & Plum' },
  { value: 'peach', label: 'Peach & Cocoa' },
];

/** Device appearance only. Palette and mode are independent of the account session. */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly media = window.matchMedia?.('(prefers-color-scheme: dark)');
  private readonly systemDark = signal(this.media?.matches ?? false);
  readonly choice = signal<ThemeChoice>(this.readMode());
  readonly palette = signal<PaletteChoice>(this.readPalette());
  readonly effectiveDark = computed(
    () => this.choice() === 'dark' || (this.choice() === 'system' && this.systemDark()),
  );

  constructor() {
    const onChange = (event: MediaQueryListEvent) => this.systemDark.set(event.matches);
    this.media?.addEventListener?.('change', onChange);
    inject(DestroyRef).onDestroy(() => this.media?.removeEventListener?.('change', onChange));
    effect(() => {
      const el = document.documentElement;
      if (this.choice() === 'system') el.removeAttribute('data-theme');
      else el.dataset['theme'] = this.choice();
      el.dataset['palette'] = this.palette();
      el.style.colorScheme = this.effectiveDark() ? 'dark' : 'light';
    });
  }

  set(choice: ThemeChoice): void {
    this.choice.set(choice);
    this.persist('spliteasy.theme', choice);
  }

  setPalette(palette: PaletteChoice): void {
    this.palette.set(palette);
    this.persist('spliteasy.palette', palette);
  }

  private persist(key: string, value: string): void {
    try {
      localStorage.setItem(key, value);
    } catch {
      /* Browser storage may be unavailable. */
    }
  }

  private readMode(): ThemeChoice {
    try {
      const value = localStorage.getItem('spliteasy.theme');
      return value === 'light' || value === 'dark' ? value : 'system';
    } catch {
      return 'system';
    }
  }

  private readPalette(): PaletteChoice {
    try {
      const value = localStorage.getItem('spliteasy.palette');
      return value === 'lilac' || value === 'peach' ? value : 'seafoam';
    } catch {
      return 'seafoam';
    }
  }
}
