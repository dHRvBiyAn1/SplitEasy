import { CdkTrapFocus } from '@angular/cdk/a11y';
import { Component, DestroyRef, HostListener, inject, input, output } from '@angular/core';

let nextDialogId = 0;

/** One named, scroll-contained, keyboard-trapped surface for every application modal. */
@Component({
  selector: 'app-modal-shell',
  imports: [CdkTrapFocus],
  templateUrl: './modal-shell.component.html',
  styleUrl: './modal-shell.component.scss',
})
export class ModalShellComponent {
  readonly heading = input.required<string>();
  readonly maxWidth = input<number>(520);
  readonly closed = output<void>();
  readonly headingId = `modal-heading-${++nextDialogId}`;

  constructor() {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    inject(DestroyRef).onDestroy(() => {
      document.body.style.overflow = previous;
      // CDK restores a connected launcher; routing/session teardown may have removed it.
      queueMicrotask(() => {
        if (document.activeElement === document.body) {
          document.querySelector<HTMLElement>('main')?.focus({ preventScroll: true });
        }
      });
    });
  }

  @HostListener('document:keydown.escape', ['$event'])
  onEscape(event: Event): void {
    event.preventDefault();
    this.closed.emit();
  }
}
