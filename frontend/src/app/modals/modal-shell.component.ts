import { Component, HostListener, input, output } from '@angular/core';

/**
 * Reusable modal chrome: dimmed backdrop + centered panel with a serif title and a
 * close button. Backdrop click and Escape both emit `closed`. Content is projected.
 */
@Component({
  selector: 'app-modal-shell',
  templateUrl: './modal-shell.component.html',
  styleUrl: './modal-shell.component.scss',
})
export class ModalShellComponent {
  readonly heading = input.required<string>();
  readonly closed = output<void>();

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closed.emit();
  }
}
