import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../core/auth/auth.service';
import { ModalService } from './modal.service';
import { NewExpenseModalComponent } from './new-expense-modal.component';

const account = { id: 'account', displayName: 'Alex', email: 'alex@test.com' };
const viewer = {
  id: 'primary',
  canonicalParticipantId: 'primary',
  accountId: account.id,
  displayName: 'Alex',
  email: account.email,
};
const guest = {
  id: 'guest',
  canonicalParticipantId: 'guest',
  accountId: null,
  displayName: 'Guest Pat',
  email: null,
};
const group = {
  id: 'g',
  name: 'Trip',
  members: [account],
  participants: [viewer, guest],
  viewerParticipantId: viewer.id,
  ownedParticipantIds: [viewer.id, 'alias'],
  createdBy: account,
  type: 'TRIP',
  simplifyDebts: true,
};

describe('participant-aware financial forms', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      imports: [NewExpenseModalComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { user: signal(account) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    for (const r of http.match('/api/me')) r.flush({}, { status: 503, statusText: 'Unavailable' });
    http.verify();
    TestBed.resetTestingModule();
  });
  function open(edit = false, response = group) {
    TestBed.inject(ModalService).openExpense('g', edit ? 'e' : undefined);
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    http.expectOne('/api/groups/g').flush(response);
    return fixture;
  }
  function fill(root: HTMLElement, selector: string, value: string) {
    const input = root.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }
  it('includes guests and uses explicit viewer identity for a new equal split', () => {
    const fixture = open();
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).toContain('Guest');
    expect(root.textContent).not.toContain('(null)');
    fill(root, '#ne-desc', 'Dinner');
    fill(root, '#ne-amt', '12');
    fixture.detectChanges();
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    const save = http.expectOne('/api/groups/g/expenses');
    expect(save.request.body).toMatchObject({
      paidByUserId: 'primary',
      participantUserIds: ['primary', 'guest'],
    });
    save.flush({}, { status: 503, statusText: 'Unavailable' });
  });
  it('retains distinct historical aliases and typed zero inputs while explaining their owner', () => {
    const fixture = open(true);
    http.expectOne('/api/groups/g/expenses/e').flush({
      id: 'e',
      groupId: 'g',
      version: 2,
      description: 'Old dinner',
      amountMinor: 1200,
      currency: 'USD',
      category: 'FOOD_DRINK',
      spentOn: '2026-10-26',
      paidBy: viewer,
      splitType: 'PERCENTAGE',
      splits: [
        { userId: 'primary', value: 10000 },
        { userId: 'alias', value: 0 },
      ],
      participants: [
        { user: viewer, shareMinor: 1200 },
        {
          user: {
            ...guest,
            id: 'alias',
            canonicalParticipantId: 'primary',
            accountId: account.id,
            displayName: 'Former guest',
          },
          shareMinor: 0,
        },
      ],
    });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelectorAll('.prow')).toHaveLength(3);
    expect(root.textContent).toContain('same account');
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    const save = http.expectOne('/api/groups/g/expenses/e');
    expect(save.request.body.splits).toEqual([
      { userId: 'primary', value: 10000 },
      { userId: 'alias', value: 0 },
    ]);
    save.flush({}, { status: 409, statusText: 'Conflict' });
  });

  it('defaults to one canonical person when active ownership aliases are present', () => {
    const fixture = open(false, {
      ...group,
      participants: [{ ...viewer, id: 'alias' }, viewer, guest],
    });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelectorAll('.prow')).toHaveLength(2);
    fill(root, '#ne-desc', 'Dinner');
    fill(root, '#ne-amt', '12');
    fixture.detectChanges();
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    const save = http.expectOne('/api/groups/g/expenses');
    expect(save.request.body.participantUserIds).toEqual(['primary', 'guest']);
    save.flush({}, { status: 503, statusText: 'Unavailable' });
  });

  it('shows unavailable original retry identities and dispatches the unchanged legacy draft only on explicit retry', () => {
    const request = {
      description: 'Original',
      paidByUserId: 'alias',
      amountMinor: 1000,
      currency: 'KWD',
      splitType: 'PERCENTAGE',
      splits: [
        { userId: 'primary', value: 10000 },
        { userId: 'alias', value: 0 },
      ],
    };
    sessionStorage.setItem(
      'spliteasy.pending-write.v1.account.expense',
      JSON.stringify({ owner: 'account', scope: 'g', operationId: 'original-operation', request }),
    );
    const fixture = open();
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).toContain('original identities are absent');
    expect(root.textContent).toContain('alias');
    expect(root.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);
    http.expectNone('/api/groups/g/expenses');
    [...root.querySelectorAll<HTMLButtonElement>('button')]
      .find((b) => b.textContent?.includes('Retry original expense'))!
      .click();
    const retry = http.expectOne('/api/groups/g/expenses');
    expect(retry.request.body).toEqual({ ...request, operationId: 'original-operation' });
    retry.flush({}, { status: 503, statusText: 'Unavailable' });
  });
});
