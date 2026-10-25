import { ProfileComponent } from '../profile/profile.component';
import { SettleUpModalComponent } from './settle-up-modal.component';
import { DashboardService } from '../dashboard/dashboard.service';
import { provideRouter } from '@angular/router';
import { DashboardComponent } from '../dashboard/dashboard.component';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../core/auth/auth.service';
import { ModalService } from './modal.service';
import { NewExpenseModalComponent } from './new-expense-modal.component';

const me = { id: 'currency-me', email: 'currency-me@example.test', displayName: 'Alex' };
const other = { id: 'currency-other', email: 'currency-other@example.test', displayName: 'Noor' };
const group = {
  id: 'currency-group',
  name: 'Trip',
  type: 'TRIP',
  createdBy: me,
  members: [me, other],
  simplifyDebts: true,
  createdAt: '2026-10-24T00:00:00Z',
};
const profile = {
  ...me,
  emailVerified: false,
  phone: '',
  avatarColor: '#12A57E',
  preferredCurrency: 'USD',
  createdAt: '2026-10-24T00:00:00Z',
};

describe('denominated financial forms', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    TestBed.configureTestingModule({
      imports: [NewExpenseModalComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).login({ email: me.email, password: 'password123' }).subscribe();
    http.expectOne('/api/auth/login').flush({
      accessToken: 'currency-access-token',
      sessionId: 'currency-session',
      tokenType: 'Bearer',
      expiresIn: 3600,
      user: me,
    });
  });
  afterEach(() => {
    try {
      http?.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('restores an old USD expense draft independently of a later KWD preference', () => {
    const operationId = '00000000-0000-4000-8000-000000000016';
    sessionStorage.setItem(
      'spliteasy.pending-write.v1.' + me.id + '.expense',
      JSON.stringify({
        owner: me.id,
        scope: group.id,
        operationId,
        sessionVersion: 0,
        request: {
          description: 'Legacy USD draft',
          amountCents: 1234,
          paidByUserId: me.id,
          participantUserIds: [me.id, other.id],
          category: 'OTHER',
          spentOn: '2026-10-24',
        },
      }),
    );
    TestBed.inject(ModalService).openExpense(group.id);
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/' + group.id).flush(group);
    http.expectOne('/api/me').flush({ ...profile, preferredCurrency: 'KWD' });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector<HTMLSelectElement>('#ne-currency')!.value).toBe('USD');
    expect(root.querySelector<HTMLInputElement>('#ne-amt')!.value).toBe('12.34');
    [...root.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === 'Retry original expense')!
      .click();
    const save = http.expectOne({ method: 'POST', url: '/api/groups/' + group.id + '/expenses' });
    expect(save.request.body).toMatchObject({ currency: 'USD', amountMinor: 1234, operationId });
    save.flush({});
    http
      .expectOne('/api/dashboard')
      .flush({ groups: [], people: [], settlements: [], activity: [], totals: [], groupCount: 1 });
  });

  it('saves KWD as a default for future drafts through the profile currency catalogue', () => {
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    http.expectOne('/api/me').flush(profile);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    root.querySelector<HTMLButtonElement>('.pcard__edit')!.click();
    fixture.detectChanges();
    const kwd = [...root.querySelectorAll<HTMLButtonElement>('.chip')].find(
      (button) => button.textContent?.trim() === 'KWD',
    );
    expect(kwd).toBeDefined();
    kwd!.click();
    fixture.detectChanges();
    root.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    const save = http.expectOne({ method: 'PATCH', url: '/api/me' });
    expect(save.request.body.preferredCurrency).toBe('KWD');
    save.flush({ ...profile, preferredCurrency: 'KWD' });
    fixture.detectChanges();
    expect(root.textContent).toContain('KWD');
  });

  it('restores a pre-currency uncertain USD payment and retries the same operation identity', () => {
    const operationId = '00000000-0000-4000-8000-000000000015';
    sessionStorage.setItem(
      'spliteasy.pending-write.v1.' + me.id + '.payment',
      JSON.stringify({
        owner: me.id,
        scope: group.id,
        operationId,
        sessionVersion: 0,
        request: { payerUserId: me.id, payeeUserId: other.id, amountCents: 1234 },
        draft: { groupId: group.id, groupName: group.name, counterparty: other, netCents: -1234 },
      }),
    );
    TestBed.inject(ModalService).openSettle();
    const fixture = TestBed.createComponent(SettleUpModalComponent);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector<HTMLInputElement>('input')!.value).toBe('12.34');
    expect(root.textContent).toContain('USD');
    [...root.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === 'Retry original payment')!
      .click();
    const save = http.expectOne({ method: 'POST', url: '/api/groups/' + group.id + '/payments' });
    expect(save.request.body).toEqual({
      payerUserId: me.id,
      payeeUserId: other.id,
      amountMinor: 1234,
      currency: 'USD',
      operationId,
    });
    save.flush({
      id: 'old-usd-payment',
      payer: me,
      payee: other,
      amountMinor: 1234,
      currency: 'USD',
      createdAt: '2026-10-24T00:00:00Z',
    });
    http
      .expectOne('/api/dashboard')
      .flush({ groups: [], people: [], settlements: [], activity: [], totals: [], groupCount: 1 });
  });

  it('edits KWD without relabelling its currency or changing percentage basis points', () => {
    TestBed.inject(ModalService).openExpense(group.id, 'kwd-edit');
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/' + group.id).flush(group);
    http.expectOne('/api/me').flush(profile);
    http.expectOne('/api/groups/' + group.id + '/expenses/kwd-edit').flush({
      id: 'kwd-edit',
      groupId: group.id,
      description: 'KWD original',
      currency: 'KWD',
      amountMinor: 1234,
      paidBy: me,
      splitType: 'PERCENTAGE',
      splits: [
        { userId: me.id, value: 5000 },
        { userId: other.id, value: 5000 },
      ],
      participants: [
        { user: me, shareMinor: 617 },
        { user: other, shareMinor: 617 },
      ],
      category: 'OTHER',
      spentOn: '2026-10-24',
      version: 0,
      createdAt: '2026-10-24T00:00:00Z',
    });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const currency = root.querySelector<HTMLSelectElement>('#ne-currency')!;
    expect(currency.value).toBe('KWD');
    expect(currency.disabled).toBe(true);
    expect(root.querySelector<HTMLInputElement>('#ne-amt')!.value).toBe('1.234');
    expect(root.querySelector<HTMLInputElement>('.prow__val')!.value).toBe('50.00');
    const description = root.querySelector<HTMLInputElement>('#ne-desc')!;
    description.value = 'Edited description';
    description.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    const save = http.expectOne({
      method: 'PUT',
      url: '/api/groups/' + group.id + '/expenses/kwd-edit',
    });
    expect(save.request.body).toMatchObject({
      currency: 'KWD',
      amountMinor: 1234,
      expectedVersion: 0,
      splits: [
        { userId: me.id, value: 5000 },
        { userId: other.id, value: 5000 },
      ],
    });
    save.flush({});
    http
      .expectOne('/api/dashboard')
      .flush({ groups: [], people: [], settlements: [], activity: [], totals: [], groupCount: 1 });
  });

  it('settles KWD using the obligation denomination and its three-decimal amount', () => {
    const settlement = {
      groupId: group.id,
      groupName: group.name,
      counterparty: other,
      netMinor: -1234,
      currency: 'KWD' as const,
    };
    TestBed.inject(DashboardService).ensureLoaded();
    http.expectOne('/api/dashboard').flush({
      groups: [],
      people: [],
      settlements: [settlement],
      activity: [],
      totals: [],
      groupCount: 1,
    });
    TestBed.inject(ModalService).openSettle(settlement);
    const fixture = TestBed.createComponent(SettleUpModalComponent);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector<HTMLInputElement>('input')!.value).toBe('1.234');
    expect(root.textContent).toContain('KWD');
    [...root.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.trim() === 'Record payment')!
      .click();
    const save = http.expectOne({ method: 'POST', url: '/api/groups/' + group.id + '/payments' });
    expect(save.request.body).toMatchObject({
      currency: 'KWD',
      amountMinor: 1234,
      payerUserId: me.id,
      payeeUserId: other.id,
    });
    expect(save.request.body.amountCents).toBeUndefined();
    save.flush({
      id: 'kwd-payment',
      payer: me,
      payee: other,
      amountMinor: 1234,
      currency: 'KWD',
      createdAt: '2026-10-24T00:00:00Z',
    });
    http
      .expectOne('/api/dashboard')
      .flush({ groups: [], people: [], settlements: [], activity: [], totals: [], groupCount: 1 });
  });

  it('renders separate USD and JPY headlines, group balances and People rows', () => {
    const fixture = TestBed.createComponent(DashboardComponent);
    http.expectOne('/api/dashboard').flush({
      totalNetMinor: null,
      owedMinor: null,
      oweMinor: null,
      owedPeopleCount: 1,
      owePeopleCount: 1,
      groupCount: 1,
      totals: [
        {
          currency: 'USD',
          totalNetMinor: 1000,
          owedMinor: 1000,
          oweMinor: 0,
          owedPeopleCount: 1,
          owePeopleCount: 0,
        },
        {
          currency: 'JPY',
          totalNetMinor: -1000,
          owedMinor: 0,
          oweMinor: 1000,
          owedPeopleCount: 0,
          owePeopleCount: 1,
        },
      ],
      groups: [
        {
          ...group,
          memberCount: 2,
          totalSpentMinor: null,
          youAreOwedMinor: null,
          youOweMinor: null,
          netMinor: null,
          totals: [
            {
              currency: 'USD',
              totalSpentMinor: 2000,
              youAreOwedMinor: 1000,
              youOweMinor: 0,
              netMinor: 1000,
            },
            {
              currency: 'JPY',
              totalSpentMinor: 2000,
              youAreOwedMinor: 0,
              youOweMinor: 1000,
              netMinor: -1000,
            },
          ],
        },
      ],
      people: [
        { user: other, netMinor: 1000, currency: 'USD' },
        { user: other, netMinor: -1000, currency: 'JPY' },
      ],
      settlements: [],
      activity: [],
    });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const headline = root.querySelector('.balance-core')!.textContent!;
    expect(headline).toContain('$10.00');
    expect(headline).toContain('JPY');
    expect(headline).toContain('1,000');
    expect(root.querySelectorAll('.friend-object')).toHaveLength(2);
    expect(root.querySelector('.group-object__balance')!.textContent).toContain('JPY');
  });

  it('freezes the shown draft denomination once the user interacts before profile loading', () => {
    TestBed.inject(ModalService).openExpense(group.id);
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/' + group.id).flush(group);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const description = root.querySelector<HTMLInputElement>('#ne-desc')!;
    description.value = 'Already drafting in USD';
    description.dispatchEvent(new Event('input', { bubbles: true }));
    http.expectOne('/api/me').flush({ ...profile, preferredCurrency: 'KWD' });
    fixture.detectChanges();
    expect(root.querySelector<HTMLSelectElement>('#ne-currency')!.value).toBe('USD');
  });

  it('rejects fractional JPY before dispatching an expense', () => {
    TestBed.inject(ModalService).openExpense(group.id);
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/' + group.id).flush(group);
    http.expectOne('/api/me').flush(profile);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const currency = root.querySelector<HTMLSelectElement>('#ne-currency')!;
    currency.value = 'JPY';
    currency.dispatchEvent(new Event('change', { bubbles: true }));
    const description = root.querySelector<HTMLInputElement>('#ne-desc')!;
    description.value = 'Tokyo lunch';
    description.dispatchEvent(new Event('input', { bubbles: true }));
    const amount = root.querySelector<HTMLInputElement>('#ne-amt')!;
    amount.value = '100.5';
    amount.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    expect(
      http.match({ method: 'POST', url: '/api/groups/' + group.id + '/expenses' }),
    ).toHaveLength(0);
    expect(root.textContent).toContain('JPY uses 0 decimal places');
  });

  it('records 1.234 KWD as 1234 integer minor units with explicit denomination', () => {
    TestBed.inject(ModalService).openExpense(group.id);
    const fixture = TestBed.createComponent(NewExpenseModalComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/' + group.id).flush(group);
    for (const request of http.match('/api/me')) request.flush(profile);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    const currency = root.querySelector<HTMLSelectElement>('#ne-currency');
    expect(currency).not.toBeNull();
    currency!.value = 'KWD';
    currency!.dispatchEvent(new Event('change', { bubbles: true }));
    const description = root.querySelector<HTMLInputElement>('#ne-desc')!;
    description.value = 'Kuwait lunch';
    description.dispatchEvent(new Event('input', { bubbles: true }));
    const amount = root.querySelector<HTMLInputElement>('#ne-amt')!;
    amount.value = '1.234';
    amount.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    const save = http.expectOne({ method: 'POST', url: '/api/groups/' + group.id + '/expenses' });
    expect(save.request.body).toMatchObject({ currency: 'KWD', amountMinor: 1234 });
    expect(save.request.body.amountCents).toBeUndefined();
    expect(save.request.headers.get('X-Ledger-Version')).toBe('2');
    save.flush({
      id: 'kwd-expense',
      groupId: group.id,
      description: 'Kuwait lunch',
      amountMinor: 1234,
      currency: 'KWD',
      participants: [],
      paidBy: me,
      splitType: 'EQUAL',
      category: 'FOOD_DRINK',
      spentOn: '2026-10-24',
      createdAt: '2026-10-24T00:00:00Z',
      version: 0,
    });
    http
      .expectOne('/api/dashboard')
      .flush({ groups: [], people: [], settlements: [], activity: [], totals: [], groupCount: 0 });
  });
});
