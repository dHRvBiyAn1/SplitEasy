import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AuthService } from '../core/auth/auth.service';
import { GroupResponse } from '../groups/group.models';
import { PendingWritesService } from '../core/pending-writes.service';
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

describe('repeat control on a new group expense', () => {
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
    TestBed.inject(ModalService).openExpense(group.id);
    fixture = TestBed.createComponent(NewExpenseModalComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/g').flush(group);
    fixture.detectChanges();
    root = fixture.nativeElement;
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

  function type(selector: string, value: string) {
    const input = root.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
  }
  function submit() {
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
  }

  it('posts a one-off expense when Repeat is Never', () => {
    type('#ne-desc', 'Rent');
    type('#ne-amt', '10');
    submit();
    http
      .expectOne({ method: 'POST', url: '/api/groups/g/expenses' })
      .error(new ProgressEvent('error'));
  });

  it('creates a schedule anchored on the chosen date instead of an expense', () => {
    type('#ne-desc', 'Rent');
    type('#ne-amt', '10');
    type('#ne-date', '2026-03-01');
    const repeat = root.querySelector<HTMLSelectElement>('#ne-repeat')!;
    repeat.value = 'MONTHLY';
    repeat.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();
    submit();
    const req = http.expectOne({ method: 'POST', url: '/api/groups/g/recurring' });
    expect(req.request.body).toMatchObject({
      frequency: 'MONTHLY',
      anchorDate: '2026-03-01',
      template: { description: 'Rent', amountMinor: 1000, paidByUserId: me.id },
    });
    expect(typeof req.request.body.timeZone).toBe('string');
    req.error(new ProgressEvent('error'));
    http.expectNone('/api/groups/g/expenses');
  });

  function fillRepeating(description = 'Rent') {
    type('#ne-desc', description);
    type('#ne-amt', '10');
    type('#ne-date', '2026-03-01');
    const repeat = root.querySelector<HTMLSelectElement>('#ne-repeat')!;
    repeat.value = 'MONTHLY';
    repeat.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();
  }

  it('keeps one operationId through an uncertain error and reuses it on retry', () => {
    fillRepeating();
    submit();
    const first = http.expectOne({ method: 'POST', url: '/api/groups/g/recurring' });
    expect(first.request.body.operationId).toMatch(/^[0-9a-f-]{36}$/);
    first.error(new ProgressEvent('error'));
    fixture.detectChanges();
    submit();
    const retry = http.expectOne({ method: 'POST', url: '/api/groups/g/recurring' });
    expect(retry.request.body.operationId).toBe(first.request.body.operationId);
    retry.error(new ProgressEvent('error'));
  });

  it('mints a new operationId when the draft changed, and clears it after success', () => {
    fillRepeating();
    submit();
    const first = http.expectOne({ method: 'POST', url: '/api/groups/g/recurring' });
    first.error(new ProgressEvent('error'));
    fixture.detectChanges();
    type('#ne-amt', '12');
    submit();
    const changed = http.expectOne({ method: 'POST', url: '/api/groups/g/recurring' });
    expect(changed.request.body.operationId).not.toBe(first.request.body.operationId);
    changed.flush({ id: 's' });
    http.match('/api/dashboard').forEach((r) => r.flush({}));
    const pending = TestBed.inject(PendingWritesService);
    expect(pending.read('recurring')).toBeNull();
  });
});
