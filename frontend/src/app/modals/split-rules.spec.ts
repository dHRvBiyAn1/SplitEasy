import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AuthService } from '../core/auth/auth.service';
import { GroupResponse } from '../groups/group.models';
import { SplitRule } from '../split-rules/split-rule.models';
import { ModalService } from './modal.service';
import { NewExpenseModalComponent } from './new-expense-modal.component';

const me = { id: 'me', email: 'me@example.test', displayName: 'Alex Rivera' };
const other = { id: 'other', email: 'other@example.test', displayName: 'Noor Kapoor' };
const third = { id: 'third', email: 'third@example.test', displayName: 'Bob Chen' };
const group: GroupResponse = {
  id: 'g',
  name: 'Flat',
  type: 'HOME',
  createdBy: me,
  members: [me, other, third],
  simplifyDebts: true,
  createdAt: '2026-10-17T00:00:00Z',
};
const rule = (over: Partial<SplitRule>): SplitRule => ({
  id: 'r1',
  groupId: 'g',
  name: 'Rent 60/40',
  splitType: 'PERCENTAGE',
  inputs: [
    { userId: 'me', value: 6000 },
    { userId: 'other', value: 4000 },
  ],
  version: 0,
  createdAt: '2026-11-25T00:00:00Z',
  updatedAt: '2026-11-25T00:00:00Z',
  ...over,
});

describe('saved split rules in the expense editor', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<NewExpenseModalComponent>;
  let root: HTMLElement;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      imports: [NewExpenseModalComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { user: signal(me) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    try {
      for (const r of http.match('/api/me'))
        if (!r.cancelled) r.flush({}, { status: 503, statusText: 'x' });
      http.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  function open() {
    TestBed.inject(ModalService).openExpense(group.id);
    fixture = TestBed.createComponent(NewExpenseModalComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/g').flush(group);
    fixture.detectChanges();
    root = fixture.nativeElement;
  }
  function type(selector: string, value: string) {
    const input = root.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
  }
  function showRules(rules: SplitRule[]) {
    root.querySelector<HTMLButtonElement>('[data-split-rules-toggle]')!.click();
    fixture.detectChanges();
    http.expectOne({ method: 'GET', url: '/api/groups/g/split-rules' }).flush(rules);
    fixture.detectChanges();
  }
  function apply(id: string) {
    const select = root.querySelector<HTMLSelectElement>('#ne-rule')!;
    select.value = id;
    select.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();
  }
  const values = () =>
    [...root.querySelectorAll<HTMLInputElement>('.prow__val')].map((i) => i.value);
  const pressed = (label: string) =>
    [...root.querySelectorAll<HTMLButtonElement>('.seg button')]
      .find((b) => b.textContent?.trim() === label)
      ?.getAttribute('aria-pressed');

  it('loads rules only when asked and prefills percentages that survive an amount change', () => {
    open();
    type('#ne-desc', 'Rent');
    type('#ne-amt', '10');
    showRules([rule({})]);
    apply('r1');

    expect(pressed('Percent')).toBe('true');
    expect(values()).toEqual(['60.00', '40.00', '']);
    type('#ne-amt', '20');
    expect(values()).toEqual(['60.00', '40.00', '']);

    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    const req = http.expectOne({ method: 'POST', url: '/api/groups/g/expenses' });
    expect(req.request.body.amountMinor).toBe(2000);
    expect(req.request.body.splits).toEqual([
      { userId: 'me', value: 6000 },
      { userId: 'other', value: 4000 },
    ]);
    req.error(new ProgressEvent('error'));
  });

  it('applies an equal rule as the included participant set', () => {
    open();
    showRules([
      rule({
        id: 'r2',
        name: 'Just us',
        splitType: 'EQUAL',
        inputs: [
          { userId: 'me', value: 1 },
          { userId: 'third', value: 1 },
        ],
      }),
    ]);
    apply('r2');
    const checked = [...root.querySelectorAll<HTMLInputElement>('.prow__check')].map(
      (c) => c.checked,
    );
    expect(checked).toEqual([true, false, true]);
  });

  it('asks for review and applies nothing when a rule names someone who left', () => {
    open();
    showRules([
      rule({
        inputs: [
          { userId: 'me', value: 5000 },
          { userId: 'gone', value: 5000 },
        ],
      }),
    ]);
    apply('r1');

    expect(root.querySelector('.rule-review')?.textContent).toContain('no longer');
    expect(pressed('Equally')).toBe('true');
    expect(root.querySelectorAll('.prow__val').length).toBe(0);
  });

  it('saves the current valid split under a name', () => {
    open();
    showRules([]);
    root.querySelectorAll<HTMLButtonElement>('.seg button')[2].click(); // Percent
    fixture.detectChanges();
    const inputs = root.querySelectorAll<HTMLInputElement>('.prow__val');
    for (const [i, v] of ['70', '30'].entries()) {
      inputs[i].value = v;
      inputs[i].dispatchEvent(new Event('input', { bubbles: true }));
    }
    fixture.detectChanges();
    type('#ne-rule-name', 'Rent 70/30');
    root.querySelector<HTMLButtonElement>('[data-save-rule]')!.click();

    const req = http.expectOne({ method: 'POST', url: '/api/groups/g/split-rules' });
    expect(req.request.body).toEqual({
      name: 'Rent 70/30',
      splitType: 'PERCENTAGE',
      inputs: [
        { userId: 'me', value: 7000 },
        { userId: 'other', value: 3000 },
      ],
    });
    req.flush(rule({ id: 'r9', name: 'Rent 70/30' }), { status: 201, statusText: 'Created' });
    fixture.detectChanges();
    expect(root.querySelector<HTMLSelectElement>('#ne-rule')!.textContent).toContain('Rent 70/30');
  });
});
