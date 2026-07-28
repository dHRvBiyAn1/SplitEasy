import { Injectable, effect, signal } from '@angular/core';

const KEY = 'spliteasy.sidebar';

/**
 * Whether the shell's sidebar is collapsed to an icon rail. Persisted like the theme
 * choice so it survives a reload, and read by the shell to size the rail.
 */
@Injectable({ providedIn: 'root' })
export class SidebarService {
  readonly collapsed = signal(this.read());

  constructor() {
    effect(() => localStorage.setItem(KEY, this.collapsed() ? 'collapsed' : 'expanded'));
  }

  toggle(): void {
    this.collapsed.set(!this.collapsed());
  }

  private read(): boolean {
    return localStorage.getItem(KEY) === 'collapsed';
  }
}
