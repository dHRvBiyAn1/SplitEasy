import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { storeTestSession } from '../core/auth/auth-test-fixture';
import { routes } from '../app.routes';
import { SessionService } from '../core/auth/session.service';

const me = { id: 'me', displayName: 'Alex', email: 'alex@test.com' };
const friend = {
  id: 'pat',
  displayName: 'Pat saved',
  createdAt: '2026-10-26T12:00:00Z',
  sharedGroupCount: 0,
  hasOpenObligations: false,
  balances: [{ currency: 'USD', netMinor: 500 }],
  sharedGroups: [],
  personalBalances: [{ currency: 'USD', netMinor: 500 }],
};
const entry = {
  version: 1,
  id: 'expense-1',
  groupId: 'ledger',
  description: 'Cinema',
  amountMinor: 1000,
  currency: 'USD',
  paidBy: { id: 'me', displayName: 'Alex', email: 'alex@test.com' },
  participantCount: 2,
  category: 'OTHER',
  spentOn: '2026-11-10',
  viewerDeltaMinor: 500,
  createdAt: '2026-11-10T12:00:00Z',
};

describe('personal expenses with one saved friend', () => {
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
    http.match('/api/dashboard'); // mutations refresh the shell overview; not under test here
    http.verify({ ignoreCancelled: true });
    TestBed.resetTestingModule();
  });
  const root = () => harness.routeNativeElement!;
  const text = () => root().textContent!;
  const button = (label: string) =>
    [...root().querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === label,
    )!;
  const input = (label: string) =>
    root().querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!;
  const type = (label: string, value: string) => {
    const field = input(label);
    field.value = value;
    field.dispatchEvent(new Event('input'));
    harness.detectChanges();
  };
  const openFriend = async (detail = friend, rows = [entry]) => {
    await harness.navigateByUrl('/friends/pat');
    http.expectOne('/api/friends/pat').flush(detail);
    harness.detectChanges();
    http.expectOne('/api/friends/pat/expenses').flush(rows);
    harness.detectChanges();
  };

  it('lists personal entries apart from groups and never renders a failed read as zero', async () => {
    await openFriend();
    expect(text()).toContain('Personal expenses');
    expect(text()).toContain('Cinema');
    expect(text()).toContain('kept outside every group');
    expect(root().querySelectorAll('app-money').length).toBeGreaterThan(0);

    await harness.navigateByUrl('/friends');
    http.expectOne('/api/friends').flush([friend]);
    harness.detectChanges();
    await harness.navigateByUrl('/friends/pat');
    http.expectOne('/api/friends/pat').flush(friend);
    harness.detectChanges();
    http.expectOne('/api/friends/pat/expenses').flush('nope', { status: 503, statusText: 'Down' });
    harness.detectChanges();
    expect(text()).toContain('Could not load personal expenses');
    expect(text()).not.toContain('Cinema');
    expect(text()).toContain('Try again');
  });

  it('adds an equal personal expense for the fixed pair with a keyed operation', async () => {
    await openFriend();
    button('Add personal expense').click();
    harness.detectChanges();
    type('Description', 'Dinner');
    type('Amount', '12.50');
    button('Pat saved paid').click();
    harness.detectChanges();
    expect(button('Add expense').disabled).toBe(false);
    button('Add expense').click();
    const request = http.expectOne({ url: '/api/friends/pat/expenses', method: 'POST' });
    expect(request.request.body.participantUserIds).toEqual(['me', 'pat']);
    expect(request.request.body.paidByUserId).toBe('pat');
    expect(request.request.body.amountMinor).toBe(1250);
    expect(request.request.body.currency).toBe('USD');
    expect(request.request.body.operationId).toEqual(expect.any(String));
    request.flush({ ...entry, id: 'new' });
    // Success asks the shell to refresh, and the refreshed detail re-reads the list.
    http.expectOne('/api/friends/pat').flush({ ...friend, balances: [] });
    harness.detectChanges();
    http.expectOne('/api/friends/pat/expenses').flush([entry]);
    expect(text()).toContain('Personal expense added.');
  });

  it('refuses an exact split whose shares do not add up to the amount', async () => {
    await openFriend();
    button('Add personal expense').click();
    harness.detectChanges();
    type('Description', 'Fuel');
    type('Amount', '20');
    button('Exact amounts').click();
    harness.detectChanges();
    type('Your share', '5');
    type('Their share', '10');
    expect(button('Add expense').disabled).toBe(true);
    type('Their share', '15');
    expect(button('Add expense').disabled).toBe(false);
    button('Add expense').click();
    const request = http.expectOne({ url: '/api/friends/pat/expenses', method: 'POST' });
    expect(request.request.body.splitType).toBe('UNEQUAL');
    expect(request.request.body.splits).toEqual([
      { userId: 'me', value: 500 },
      { userId: 'pat', value: 1500 },
    ]);
    request.flush(entry);
    http.expectOne('/api/friends/pat').flush(friend);
    harness.detectChanges();
    http.expectOne('/api/friends/pat/expenses').flush([entry]);
  });

  it('deletes at the version the reader saw and edits through the full-replace path', async () => {
    await openFriend();
    button('Edit').click();
    const detail = http.expectOne({ url: '/api/friends/pat/expenses/expense-1', method: 'GET' });
    detail.flush({ ...entry, splitType: 'EQUAL', participants: [] });
    harness.detectChanges();
    type('Amount', '30');
    button('Save changes').click();
    const update = http.expectOne({
      url: '/api/friends/pat/expenses/expense-1',
      method: 'PUT',
    });
    expect(update.request.body.expectedVersion).toBe(1);
    expect(update.request.body.amountMinor).toBe(3000);
    update.flush({ ...entry, amountMinor: 3000, version: 2 });
    http.expectOne('/api/friends/pat').flush(friend);
    harness.detectChanges();
    http.expectOne('/api/friends/pat/expenses').flush([{ ...entry, amountMinor: 3000 }]);
    harness.detectChanges();

    button('Delete').click();
    http
      .expectOne({ url: '/api/friends/pat/expenses/expense-1?expectedVersion=1', method: 'DELETE' })
      .flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/friends/pat').flush(friend);
    harness.detectChanges();
    http.expectOne('/api/friends/pat/expenses').flush([]);
  });

  it('keeps an uncertain save for review instead of retrying it', async () => {
    await openFriend();
    button('Add personal expense').click();
    harness.detectChanges();
    type('Description', 'Taxi');
    type('Amount', '8');
    button('Add expense').click();
    http
      .expectOne({ url: '/api/friends/pat/expenses', method: 'POST' })
      .flush(null, { status: 0, statusText: 'Network' });
    harness.detectChanges();
    expect(text()).toContain('may already have been saved');
    expect(root().querySelectorAll('button[disabled]').length).toBeGreaterThan(0);
    const key = Object.keys(sessionStorage).find((k) => k.includes('pending-write'));
    expect(sessionStorage.getItem(key!)!).toContain('friend:pat');
    // No second POST happens on its own; only the explicit retry dispatches.
    http.expectNone({ url: '/api/friends/pat/expenses', method: 'POST' });
    button('Retry the original draft').click();
    const retry = http.expectOne({ url: '/api/friends/pat/expenses', method: 'POST' });
    expect(retry.request.body.description).toBe('Taxi');
    retry.flush(entry);
    http.expectOne('/api/friends/pat').flush(friend);
    harness.detectChanges();
    http.expectOne('/api/friends/pat/expenses').flush([entry]);
    expect(sessionStorage.getItem(key!)).toBeNull();
  });

  it('drops the previous friend on switch and clears on session reset', async () => {
    await openFriend();
    await harness.navigateByUrl('/friends');
    http.expectOne('/api/friends').flush([friend, { ...friend, id: 'old', displayName: 'Old' }]);
    harness.detectChanges();
    await harness.navigateByUrl('/friends/old');
    http.expectOne('/api/friends/old').flush({ ...friend, id: 'old', displayName: 'Old' });
    harness.detectChanges();
    http.expectOne('/api/friends/old/expenses').flush([]);
    harness.detectChanges();
    expect(text()).toContain('No personal expenses with Old yet.');
    expect(text()).not.toContain('Cinema');

    TestBed.inject(SessionService).reset();
    harness.detectChanges();
    expect(text()).not.toContain('No personal expenses with Old yet.');
  });
});
