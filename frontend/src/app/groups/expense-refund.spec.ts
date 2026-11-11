import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { storeTestSession } from '../core/auth/auth-test-fixture';
import { GroupDetailComponent } from './group-detail.component';

const alice = { id: 'alice', displayName: 'Alice Recorder', email: 'alice@example.test' };
const bob = { id: 'bob', displayName: 'Bob Payer', email: 'bob@example.test' };
const summary = {
  id: 'e',
  version: 2,
  description: 'Dinner',
  amountMinor: 10000,
  currency: 'USD',
  paidBy: bob,
  participantCount: 2,
  category: 'OTHER',
  spentOn: '2026-10-20',
  viewerDeltaMinor: -5000,
  createdAt: '2026-10-25T12:00:00Z',
};
const snapshot = {
  ...summary,
  groupId: 'g',
  splitType: 'EQUAL',
  participantUserIds: [alice.id, bob.id],
  participants: [
    { user: alice, shareMinor: 5000 },
    { user: bob, shareMinor: 5000 },
  ],
};

describe('recording a refund from an expense row', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  const button = (text: string) =>
    Array.from(harness.routeNativeElement!.querySelectorAll('button')).find(
      (element) => element.textContent!.trim() === text,
    ) as HTMLButtonElement | undefined;

  async function openReceipt() {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'groups/:id', component: GroupDetailComponent }]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession(alice);
    harness = await RouterTestingHarness.create('/groups/g');
    http
      .expectOne('/api/groups/g')
      .flush({ id: 'g', name: 'Trip', type: 'HOME', members: [alice, bob] });
    http.expectOne('/api/groups/g/expenses').flush([summary]);
    http.expectOne('/api/groups/g/balances/mine').flush([]);
    http.expectOne('/api/groups/g/balances').flush({ balances: [] });
    harness.detectChanges();
    (harness.routeNativeElement!.querySelector('.xitem button') as HTMLButtonElement).click();
    harness.detectChanges();
    http.expectOne('/api/groups/g/expenses/e').flush(snapshot);
    harness.detectChanges();
  }

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('posts a positive refund in the expense currency and lists it', async () => {
    await openReceipt();

    button('Record refund')!.click();
    harness.detectChanges();
    http.expectOne('/api/groups/g/expenses/e/refunds').flush([]);
    harness.detectChanges();

    const element = harness.routeNativeElement!;
    const amount = element.querySelector<HTMLInputElement>('[data-test="refund-amount"]')!;
    amount.value = '30.00';
    amount.dispatchEvent(new Event('input'));
    const date = element.querySelector<HTMLInputElement>('[data-test="refund-date"]')!;
    date.value = '2026-10-22';
    date.dispatchEvent(new Event('input'));
    harness.detectChanges();
    element
      .querySelector<HTMLFormElement>('[data-test="refund-form"]')!
      .dispatchEvent(new Event('submit'));
    harness.detectChanges();

    const request = http.expectOne('/api/groups/g/expenses/e/refunds');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toMatchObject({
      amountMinor: 3000,
      currency: 'USD',
      refundedOn: '2026-10-22',
    });
    expect(request.request.body.operationId).toBeTruthy();
    request.flush({
      id: 'r1',
      expenseId: 'e',
      amountMinor: 3000,
      currency: 'USD',
      receivedBy: bob,
      refundedOn: '2026-10-22',
      note: null,
      recordedBy: alice,
      createdAt: '2026-10-25T13:00:00Z',
      version: 0,
      deletedAt: null,
      participants: [
        { user: alice, shareMinor: 1500, currency: 'USD' },
        { user: bob, shareMinor: 1500, currency: 'USD' },
      ],
    });
    harness.detectChanges();

    // A successful financial write invalidates the group's reads, as every mutation here does.
    http.expectOne('/api/groups/g');
  });

  it('lists an existing refund in the receipt', async () => {
    await openReceipt();

    button('Record refund')!.click();
    harness.detectChanges();
    http.expectOne('/api/groups/g/expenses/e/refunds').flush([
      {
        id: 'r1',
        expenseId: 'e',
        amountMinor: 3000,
        currency: 'USD',
        receivedBy: bob,
        refundedOn: '2026-10-22',
        note: 'Returned a round',
        recordedBy: alice,
        createdAt: '2026-10-25T13:00:00Z',
        version: 0,
        deletedAt: null,
        participants: [{ user: alice, shareMinor: 1500, currency: 'USD' }],
      },
    ]);
    harness.detectChanges();

    const text = harness.routeNativeElement!.textContent ?? '';
    expect(text).toContain('Refunded');
    expect(text).toContain('Returned a round');
  });

  it('refuses an amount above what is left to refund without posting', async () => {
    await openReceipt();

    button('Record refund')!.click();
    harness.detectChanges();
    http.expectOne('/api/groups/g/expenses/e/refunds').flush([]);
    harness.detectChanges();

    const element = harness.routeNativeElement!;
    const amount = element.querySelector<HTMLInputElement>('[data-test="refund-amount"]')!;
    amount.value = '120.00';
    amount.dispatchEvent(new Event('input'));
    harness.detectChanges();
    element
      .querySelector<HTMLFormElement>('[data-test="refund-form"]')!
      .dispatchEvent(new Event('submit'));
    harness.detectChanges();

    http.expectNone('/api/groups/g/expenses/e/refunds');
    expect(element.textContent).toContain('left to refund');
  });
});
