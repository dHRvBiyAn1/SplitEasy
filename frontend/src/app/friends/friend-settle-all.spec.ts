import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { storeTestSession } from '../core/auth/auth-test-fixture';
import { routes } from '../app.routes';

const me = { id: 'me', displayName: 'Alex', email: 'alex@test.com' };
const friend = {
  id: 'pat',
  displayName: 'Pat saved',
  createdAt: '2026-10-26T12:00:00Z',
  sharedGroupCount: 2,
  hasOpenObligations: true,
  balances: [{ currency: 'USD', netMinor: 600 }],
  sharedGroups: [],
  personalBalances: [],
};
const preview = {
  currency: 'USD',
  allocations: [
    { scopeId: 'g1', groupName: 'Trip', payerId: 'pat', payeeId: 'me', amountMinor: 500 },
    { scopeId: 'g2', groupName: 'Flat', payerId: 'me', payeeId: 'pat', amountMinor: 200 },
    { scopeId: 'p1', groupName: null, payerId: 'pat', payeeId: 'me', amountMinor: 300 },
  ],
  skipped: [{ scopeId: 'g3', groupName: 'Old', reason: 'Archived group' }],
  netMinor: 600,
};

describe('settle everything with one friend', () => {
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
    await harness.navigateByUrl('/friends/pat');
    http.expectOne('/api/friends/pat').flush(friend);
    harness.detectChanges();
    http.expectOne('/api/friends/pat/expenses').flush([]);
    http.expectOne('/api/friends/pat/payments').flush([]);
    harness.detectChanges();
  });
  afterEach(() => {
    http.match('/api/dashboard');
    http.verify({ ignoreCancelled: true });
    TestBed.resetTestingModule();
  });
  const root = () => harness.routeNativeElement!;
  const text = () => root().textContent!;
  const button = (label: string) =>
    [...root().querySelectorAll<HTMLButtonElement>('button')].find((b) =>
      b.textContent?.trim().startsWith(label),
    )!;
  const review = () => {
    button('Settle all in USD').click();
    http.expectOne('/api/friends/pat/settlement-preview?currency=USD').flush(preview);
    harness.detectChanges();
  };

  it('reviews every scope with direction, skipped groups and the net', () => {
    review();
    expect(text()).toContain('Trip');
    expect(text()).toContain('Flat');
    expect(text()).toContain('Personal');
    expect(text()).toContain('Pat saved pays you');
    expect(text()).toContain('You pay Pat saved');
    expect(text()).toContain('Old');
    expect(text()).toContain('Archived group');
  });

  it('commits the previewed allocations and keeps one operation id through an uncertain error', () => {
    review();
    button('Confirm').click();
    const first = http.expectOne({ url: '/api/friends/pat/settlements', method: 'POST' });
    expect(first.request.body.allocations).toEqual(preview.allocations);
    expect(first.request.body.currency).toBe('USD');
    first.flush(null, { status: 503, statusText: 'Unavailable' });
    harness.detectChanges();
    expect(text()).toContain('Not confirmed');

    button('Confirm').click();
    const retry = http.expectOne({ url: '/api/friends/pat/settlements', method: 'POST' });
    expect(retry.request.body.operationId).toBe(first.request.body.operationId);
    retry.flush({ id: 'b1', currency: 'USD', allocations: preview.allocations, netMinor: 600 });
    http.expectOne('/api/friends/pat').flush(friend);
    http.expectOne('/api/friends/pat/expenses').flush([]);
    http.expectOne('/api/friends/pat/payments').flush([]);
    harness.detectChanges();
    expect(text()).not.toContain('Pat saved pays you');
  });

  it('a 409 asks for a fresh review and uses a new operation id afterwards', () => {
    review();
    button('Confirm').click();
    const first = http.expectOne({ url: '/api/friends/pat/settlements', method: 'POST' });
    first.flush({ message: 'changed' }, { status: 409, statusText: 'Conflict' });
    http.expectOne('/api/friends/pat/settlement-preview?currency=USD').flush({
      ...preview,
      allocations: preview.allocations.slice(0, 1),
      netMinor: 500,
    });
    harness.detectChanges();
    expect(text()).toContain('Balances changed');
    button('Confirm').click();
    const second = http.expectOne({ url: '/api/friends/pat/settlements', method: 'POST' });
    expect(second.request.body.operationId).not.toBe(first.request.body.operationId);
    expect(second.request.body.allocations.length).toBe(1);
    second.flush({ id: 'b2', currency: 'USD', allocations: [], netMinor: 500 });
    http.expectOne('/api/friends/pat').flush(friend);
    http.expectOne('/api/friends/pat/expenses').flush([]);
    http.expectOne('/api/friends/pat/payments').flush([]);
  });
});
