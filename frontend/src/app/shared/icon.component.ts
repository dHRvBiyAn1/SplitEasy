import { Component, input } from '@angular/core';
const PATHS = {
  overview: 'M3 10 12 3l9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1Z',
  groups:
    'M9 5a3 3 0 1 0 0 6 3 3 0 0 0 0-6ZM3 21v-3a6 6 0 0 1 12 0v3M16 5a3 3 0 0 1 0 6m2 3a5 5 0 0 1 3 4v3',
  expense: 'M7 3h10l3 3v15l-4-2-4 2-4-2-4 2V6ZM12 8v8m-4-4h8',
  profile: 'M12 4a4 4 0 1 0 0 8 4 4 0 0 0 0-8ZM4 21v-2a7 7 0 0 1 7-7h2a7 7 0 0 1 7 7v2',
  appearance:
    'M12 3a9 9 0 1 0 0 18h1.5a2.5 2.5 0 0 0 1.6-4.4c-.7-.6-.3-1.6.6-1.6h1.8A3.5 3.5 0 0 0 21 11.5 8.7 8.7 0 0 0 12 3ZM7 11h1m2-4h1m4 1h1',
  sun: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8ZM12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5',
  moon: 'M20.5 14A8.5 8.5 0 0 1 10 3.5 8.5 8.5 0 1 0 20.5 14Z',
  plus: 'M12 5v14M5 12h14',
  close: 'M6 6l12 12M18 6 6 18',
  arrow: 'M5 12h14m-6-6 6 6-6 6',
  settle: 'M4 8h13l-3-3M20 16H7l3 3',
} as const;
@Component({
  selector: 'app-icon',
  template: '<svg viewBox="0 0 24 24" aria-hidden="true"><path [attr.d]="paths[name()]" /></svg>',
  styles:
    ':host{display:inline-flex;flex:none;width:22px;height:22px}svg{width:100%;height:100%;fill:none;stroke:currentColor;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}',
})
export class IconComponent {
  readonly name = input.required<keyof typeof PATHS>();
  protected readonly paths = PATHS;
}
