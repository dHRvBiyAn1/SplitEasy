import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { storeTestSession } from '../core/auth/auth-test-fixture';
import { routes } from '../app.routes';
import { ModalService } from '../modals/modal.service';

const me = { id: 'me', displayName: 'Alex', email: 'alex@test.com' };
const pat = { id: 'pat', displayName: 'Pat', email: 'pat@test.com' };
const friend = {
  id: 'pat',
  displayName: 'Pat saved',
  createdAt: '2026-10-26T12:00:00Z',
  sharedGroupCount: 1,
  hasOpenObligations: true,
  balances: [
    { currency: 'USD', netMinor: 1500 },
    { currency: 'JPY', netMinor: -300 },
  ],
  sharedGroups: [
    {
      id: 'g1',
      name: 'Trip',
      simplifyDebts: true,
      balances: [{ currency: 'USD', netMinor: 1000 }],
    },
  ],
  personalBalances: [
    { currency: 'USD', netMinor: 500 },
    { currency: 'JPY', netMinor: -300 },
  ],
};
const payment = {
  id: 'pay-1',
  payer: pat,
  payee: me,
  amountMinor: 200,
  currency: 'USD',
  createdAt: '2026-11-10T12:00:00Z',
  paidOn: '2026-11-10',
  method: 'CASH',
  note: 'Lunch',
  recordedBy: me,
  version: 1,
  deletedAt: null,
};

describe('personal payments with one saved friend', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  beforeEach(async () => {
    sessionStorage.clear();
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter(routes)],
    });
    http = TestBed.inject(HttpTestingController);
    storeTestSession(me);
    harness = await RouterTestingHarness.create();
  });
  afterEach(() => {
    http.match('/api/dashboard');
    http.verify({ ignoreCancelled: true });
    TestBed.resetTestingModule();
  });
  const root = () => harness.routeNativeElement!;
  const text = () => root().textContent!;
  const button = (label: string) =>
    [...root().querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === label,
    )!;
  const open = async (rows: unknown[] = [payment]) => {
    await harness.navigateByUrl('/friends/pat');
    http.expectOne('/api/friends/pat').flush(friend);
    harness.detectChanges();
    http.expectOne('/api/friends/pat/expenses').flush([]);
    http.expectOne('/api/friends/pat/payments').flush(rows);
    harness.detectChanges();
  };

  it('breaks the balance down by scope and opens the personal settle-up prefilled', async () => {
    await open();
    expect(text()).toContain('Group debts are settled in their group');
    expect(root().querySelector('a[href="/groups/g1"]')).toBeTruthy();
    const modal = TestBed.inject(ModalService);
    button('Settle up USD').click();
    const prefill = modal.settlePrefill()!;
    expect(prefill.groupId).toBe('personal:pat');
    expect(prefill.netMinor).toBe(500);
    expect(prefill.currency).toBe('USD');
    expect(prefill.counterparty.id).toBe('pat');
    // JPY is a separate scope; nothing nets it against USD
    expect(button('Settle up JPY')).toBeTruthy();
  });

  it('lists payments with date, method, note and recorder', async () => {
    await open();
    expect(text()).toContain('2026-11-10');
    expect(text()).toContain('Cash');
    expect(text()).toContain('Lunch');
    expect(text()).toContain('Alex');
  });

  it('undoes at the seen version, keeps a stale 409 visible, and restores', async () => {
    await open();
    button('Undo').click();
    http
      .expectOne({ url: '/api/friends/pat/payments/pay-1?expectedVersion=1', method: 'DELETE' })
      .flush({ message: 'This payment changed. Reload.' }, { status: 409, statusText: 'Conflict' });
    harness.detectChanges();
    expect(text()).toContain('This payment changed. Reload.');

    button('Undo').click();
    http
      .expectOne({ url: '/api/friends/pat/payments/pay-1?expectedVersion=1', method: 'DELETE' })
      .flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/friends/pat').flush(friend);
    http.expectOne('/api/friends/pat/expenses').flush([]);
    http.expectOne('/api/friends/pat/payments').flush([]);
    harness.detectChanges();

    button('Recently undone').click();
    http
      .expectOne('/api/friends/pat/payments/deleted')
      .flush([{ payment: { ...payment, version: 2, deletedAt: 'x' }, deletedAt: 'x' }]);
    harness.detectChanges();
    button('Restore').click();
    const restore = http.expectOne({
      url: '/api/friends/pat/payments/pay-1/restore',
      method: 'POST',
    });
    expect(restore.request.body).toEqual({ expectedVersion: 2 });
    restore.flush({ ...payment, version: 3 });
    http.expectOne('/api/friends/pat').flush(friend);
    http.expectOne('/api/friends/pat/expenses').flush([]);
    http.expectOne('/api/friends/pat/payments').flush([payment]);
    http.expectOne('/api/friends/pat/payments/deleted').flush([]);
  });

  it('shows revisions for a payment', async () => {
    await open();
    button('Revisions').click();
    http.expectOne('/api/friends/pat/payments/pay-1/history').flush([
      {
        action: 'CREATED',
        actor: me,
        recordedAt: '2026-11-10T12:00:00Z',
        version: 1,
        before: null,
        after: payment,
      },
    ]);
    harness.detectChanges();
    expect(text()).toContain('CREATED');
  });
});
