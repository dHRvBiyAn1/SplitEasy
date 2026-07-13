import { Injectable, effect, signal } from '@angular/core';

export type ThemeChoice = 'system' | 'light' | 'dark';

const KEY = 'spliteasy.theme';

/**
 * User theme override. 'system' defers to prefers-color-scheme (the default); 'light'/'dark'
 * stamp data-theme on <html>, which the token overrides in _tokens.scss key off.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly choice = signal<ThemeChoice>(this.read());

  constructor() {
    effect(() => this.apply(this.choice()));
  }

  set(choice: ThemeChoice): void {
    this.choice.set(choice);
    localStorage.setItem(KEY, choice);
  }

  private apply(choice: ThemeChoice): void {
    const el = document.documentElement;
    if (choice === 'system') {
      el.removeAttribute('data-theme');
    } else {
      el.setAttribute('data-theme', choice);
    }
  }

  private read(): ThemeChoice {
    const v = localStorage.getItem(KEY);
    return v === 'light' || v === 'dark' ? v : 'system';
  }
}
