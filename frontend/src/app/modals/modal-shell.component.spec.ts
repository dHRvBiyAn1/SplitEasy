import { TestBed } from '@angular/core/testing';
import { ModalShellComponent } from './modal-shell.component';

describe('shared modal accessibility', () => {
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
