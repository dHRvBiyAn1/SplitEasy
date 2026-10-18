import { InteractivityChecker } from '@angular/cdk/a11y';
import { vi } from 'vitest';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ModalShellComponent } from './modal-shell.component';

@Component({
  imports: [ModalShellComponent],
  template: `
    <app-modal-shell heading="Settle up">
      @if (confirming()) {
        <input aria-label="Amount to settle" />
      } @else {
        <button type="button">Select transfer</button>
      }
    </app-modal-shell>
  `,
})
class ChangingDialog {
  readonly confirming = signal(false);
}

describe('shared modal accessibility', () => {
  it('recovers focus after a step removes its control and preserves retained focus', async () => {
    // jsdom has no layout; CDK visibility checks need the browser's focusability result.
    vi.spyOn(TestBed.inject(InteractivityChecker), 'isFocusable').mockReturnValue(true);
    const fixture = TestBed.createComponent(ChangingDialog);
    fixture.detectChanges();
    await fixture.whenStable();
    const heading: HTMLElement = fixture.nativeElement.querySelector('h2');
    const select: HTMLButtonElement = fixture.nativeElement.querySelector('.mdl-body button');
    select.focus();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.activeElement).toBe(select);

    fixture.componentInstance.confirming.set(true);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.activeElement).toBe(heading);

    const amount: HTMLInputElement = fixture.nativeElement.querySelector('input');
    amount.focus();
    fixture.componentInstance.confirming.set(false);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.activeElement).toBe(heading);
  });

  it('names the dialog with its visible heading', () => {
    const fixture = TestBed.createComponent(ModalShellComponent);
    fixture.componentRef.setInput('heading', 'Add expense');
    fixture.detectChanges();
    const panel: HTMLElement = fixture.nativeElement.querySelector('[role=dialog]');
    const heading = document.getElementById(panel.getAttribute('aria-labelledby') ?? '');
    expect(heading?.textContent).toBe('Add expense');
  });

  it('locks background scrolling only while mounted', () => {
    const previous = document.body.style.overflow;
    const fixture = TestBed.createComponent(ModalShellComponent);
    fixture.componentRef.setInput('heading', 'Create group');
    fixture.detectChanges();
    expect(document.body.style.overflow).toBe('hidden');
    fixture.destroy();
    expect(document.body.style.overflow).toBe(previous);
  });
});
