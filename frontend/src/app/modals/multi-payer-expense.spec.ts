import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AuthService } from '../core/auth/auth.service';
import { ExpenseResponse } from '../expenses/expense.models';
import { GroupResponse } from '../groups/group.models';
import { ModalService } from './modal.service';
import { NewExpenseModalComponent } from './new-expense-modal.component';

const me = { id: 'me', email: 'me@example.test', displayName: 'Alex Rivera' };
const other = { id: 'other', email: 'other@example.test', displayName: 'Noor Kapoor' };
const third = { id: 'third', email: 'third@example.test', displayName: 'Bob Chen' };
const group: GroupResponse = {
  id: 'g',
  name: 'Trip',
  type: 'TRIP',
  createdBy: me,
  members: [me, other, third],
  simplifyDebts: true,
  createdAt: '2026-10-17T00:00:00Z',
};
const stored: ExpenseResponse = {
  version: 2,
  id: 'e',
  groupId: 'g',
  description: 'Dinner',
  amountMinor: 1000,
  currency: 'USD',
  splitType: 'EQUAL',
  category: 'FOOD_DRINK',
  spentOn: '2026-10-17',
  paidBy: me,
  participantUserIds: [me.id, other.id],
  participants: [
    { user: me, shareMinor: 500 },
    { user: other, shareMinor: 500 },
  ],
  contributions: [
    { user: me, amountMinor: 700, currency: 'USD' },
    { user: other, amountMinor: 300, currency: 'USD' },
  ],
  createdAt: '2026-10-17T00:00:00Z',
};

describe('multi-payer expense editor', () => {
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

  function open(edit?: ExpenseResponse) {
    TestBed.inject(ModalService).openExpense(group.id, edit?.id);
    fixture = TestBed.createComponent(NewExpenseModalComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/g').flush(group);
    if (edit) http.expectOne(`/api/groups/g/expenses/${edit.id}`).flush(edit);
    fixture.detectChanges();
    root = fixture.nativeElement;
  }
  function type(selector: string, value: string) {
    const input = root.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
  }
  const payer = (id: string) => `[data-payer="${id}"]`;
  const toggle = () =>
    [...root.querySelectorAll<HTMLButtonElement>('button')].find((b) =>
      b.textContent?.includes('Multiple people'),
    )!;
  const saveButton = () => root.querySelector<HTMLButtonElement>('[type=submit]')!;
  const remainder = () => root.querySelector('.payers__remainder')?.textContent?.trim();
  function submit() {
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
  }
  function fillBasics() {
    type('#ne-desc', 'Dinner');
    type('#ne-amt', '10');
  }

  it('single payer POST omits contributions', () => {
    open();
    fillBasics();
    submit();
    const req = http.expectOne({ method: 'POST', url: '/api/groups/g/expenses' });
    expect('contributions' in req.request.body).toBe(false);
    req.error(new ProgressEvent('error'));
  });

  it('multi payer POST sends one contribution per chosen payer', () => {
    open();
    fillBasics();
    toggle().click();
    fixture.detectChanges();
    type(payer(me.id), '7');
    type(payer(other.id), '3');
    expect(saveButton().disabled).toBe(false);
    submit();
    const req = http.expectOne({ method: 'POST', url: '/api/groups/g/expenses' });
    expect(req.request.body).toMatchObject({
      paidByUserId: me.id,
      amountMinor: 1000,
      contributions: [
        { userId: me.id, amountMinor: 700 },
        { userId: other.id, amountMinor: 300 },
      ],
    });
    req.error(new ProgressEvent('error'));
  });

  it('gates save on the remainder, including after an amount change', () => {
    open();
    fillBasics();
    toggle().click();
    fixture.detectChanges();
    type(payer(me.id), '7');
    expect(remainder()).toBe('3.00 left to assign');
    expect(saveButton().disabled).toBe(true);
    type(payer(other.id), '4');
    expect(remainder()).toBe('1.00 over');
    expect(saveButton().disabled).toBe(true);
    type(payer(other.id), '3');
    expect(saveButton().disabled).toBe(false);
    type('#ne-amt', '20');
    expect(remainder()).toBe('10.00 left to assign');
    expect(saveButton().disabled).toBe(true);
  });

  it('requires the primary payer to contribute and rejects zero amounts', () => {
    open();
    fillBasics();
    toggle().click();
    fixture.detectChanges();
    type(payer(other.id), '10');
    expect(saveButton().disabled).toBe(true);
    type(payer(me.id), '0');
    expect(saveButton().disabled).toBe(true);
  });

  it('labels each amount input with full name and email', () => {
    open();
    toggle().click();
    fixture.detectChanges();
    expect(root.querySelector(payer(other.id))!.getAttribute('aria-label')).toContain(
      'Noor Kapoor (other@example.test)',
    );
    expect(root.querySelector('.payers__remainder')!.getAttribute('aria-live')).toBe('polite');
  });

  it('opens a multi-payer expense in multi mode and keeps its contributions on edit', () => {
    open(stored);
    expect(toggle().getAttribute('aria-pressed')).toBe('true');
    expect(root.querySelector<HTMLInputElement>(payer(me.id))!.value).toBe('7.00');
    expect(root.querySelector<HTMLInputElement>(payer(other.id))!.value).toBe('3.00');
    submit();
    const req = http.expectOne({ method: 'PUT', url: '/api/groups/g/expenses/e' });
    expect(req.request.body.contributions).toEqual([
      { userId: me.id, amountMinor: 700 },
      { userId: other.id, amountMinor: 300 },
    ]);
  });

  it('switching a multi-payer edit back to one payer still sends one contribution', () => {
    open(stored);
    toggle().click();
    fixture.detectChanges();
    submit();
    const req = http.expectOne({ method: 'PUT', url: '/api/groups/g/expenses/e' });
    expect(req.request.body.contributions).toEqual([{ userId: me.id, amountMinor: 1000 }]);
  });

  it('editing a single-payer expense sends no contributions', () => {
    open({ ...stored, contributions: [{ user: me, amountMinor: 1000, currency: 'USD' }] });
    submit();
    const req = http.expectOne({ method: 'PUT', url: '/api/groups/g/expenses/e' });
    expect('contributions' in req.request.body).toBe(false);
  });

  it('keeps the form open and typed amounts after a 409', () => {
    open(stored);
    type(payer(other.id), '3.00');
    submit();
    http
      .expectOne('/api/groups/g/expenses/e')
      .flush({ message: 'Reload and review' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(root.textContent).toContain('Reload and review');
    expect(root.querySelector<HTMLInputElement>(payer(me.id))!.value).toBe('7.00');
  });

  it('restores contributions from an uncertain draft and sends them unchanged', () => {
    open();
    fillBasics();
    toggle().click();
    fixture.detectChanges();
    type(payer(me.id), '7');
    type(payer(other.id), '3');
    submit();
    const first = http.expectOne({ method: 'POST', url: '/api/groups/g/expenses' });
    const original = first.request.body;
    first.error(new ProgressEvent('error'));
    fixture.destroy();
    http.match('/api/me');
    open();
    expect(toggle().getAttribute('aria-pressed')).toBe('true');
    expect(root.querySelector<HTMLInputElement>(payer(other.id))!.value).toBe('3.00');
    submit();
    const retry = http.expectOne({ method: 'POST', url: '/api/groups/g/expenses' });
    expect(retry.request.body).toEqual(original);
    retry.error(new ProgressEvent('error'));
  });
});
