import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { storeTestSession } from '../core/auth/auth-test-fixture';
import { DashboardService } from '../dashboard/dashboard.service';
import { GroupDetailComponent } from './group-detail.component';

const alice = { id: 'alice', displayName: 'Alice Recorder', email: 'alice@example.test' };
const bob = { id: 'bob', displayName: 'Bob Payer', email: 'bob@example.test' };
const summary = {
  id: 'e',
  version: 2,
  description: 'Dinner',
  amountMinor: 1234,
  currency: 'KWD',
  paidBy: bob,
  participantCount: 2,
  category: 'OTHER',
  spentOn: '2026-10-20',
  viewerDeltaMinor: -411,
  createdAt: '2026-10-25T12:00:00Z',
};
const snapshot = {
  ...summary,
  groupId: 'g',
  splitType: 'WEIGHTS',
  participants: [
    { user: alice, shareMinor: 411 },
    { user: bob, shareMinor: 823 },
  ],
  splits: [
    { userId: alice.id, value: 1 },
    { userId: bob.id, value: 2 },
  ],
};

describe('expense history and recovery through the group route', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  const button = (text: string) =>
    Array.from(harness.routeNativeElement!.querySelectorAll('button')).find(
      (element) => element.textContent!.trim() === text,
    ) as HTMLButtonElement | undefined;

  async function open(expenses: unknown[] = [summary]) {
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
    flushGroup(expenses);
    harness.detectChanges();
  }

  function flushGroup(expenses: unknown[]) {
    http
      .expectOne('/api/groups/g')
      .flush({ id: 'g', name: 'Trip', type: 'HOME', members: [alice, bob] });
    http.expectOne('/api/groups/g/expenses').flush(expenses);
    http.expectOne('/api/groups/g/balances/mine').flush([]);
    http.expectOne('/api/groups/g/balances').flush({ balances: [] });
  }

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('renders attributable chronological history with before and after money and original split inputs', async () => {
    await open();
    (harness.routeNativeElement!.querySelector('.xrow') as HTMLButtonElement).click();
    http.expectOne('/api/groups/g/expenses/e').flush(snapshot);
    harness.detectChanges();
    expect(button('Expense history')).toBeDefined();
    button('Expense history')!.click();
    http.expectOne('/api/groups/g/expenses/e/history').flush([
      {
        action: 'CREATED',
        actor: alice,
        recordedAt: '2026-10-25T12:00:00Z',
        version: 0,
        before: null,
        after: snapshot,
      },
      {
        action: 'REPLACED',
        actor: bob,
        recordedAt: '2026-10-25T13:00:00Z',
        version: 2,
        before: snapshot,
        after: { ...snapshot, description: 'Adjusted dinner', amountMinor: 1501 },
      },
    ]);
    harness.detectChanges();
    const history = harness.routeNativeElement!.querySelector('[aria-label="Expense history"]')!;
    expect(history.textContent).toContain('Alice Recorder');
    expect(history.textContent).toContain('Bob Payer');
    expect(history.textContent).toContain('Adjusted dinner');
    expect(history.textContent).toContain('KWD');
    expect(history.textContent).toContain('1.234');
    expect(history.textContent).toContain('1.501');
    expect(history.textContent).toContain('Weights');
    expect(history.textContent).toContain('Alice Recorder: 1');
    expect(history.querySelectorAll('time')[0].getAttribute('datetime')).toBe(
      '2026-10-25T12:00:00Z',
    );
    expect(harness.routeNativeElement!.querySelector('.xdetail__del')).not.toBeNull();
    http.verify();
  });
  it('deletes recoverably and restores reviewed money once using the displayed version', async () => {
    await open();
    (harness.routeNativeElement!.querySelector('.xrow') as HTMLButtonElement).click();
    http.expectOne('/api/groups/g/expenses/e').flush(snapshot);
    harness.detectChanges();
    const confirmation = vi.spyOn(window, 'confirm').mockReturnValue(true);
    (harness.routeNativeElement!.querySelector('.xdetail__del') as HTMLButtonElement).click();
    expect(confirmation).toHaveBeenCalledWith(expect.stringContaining('Deleted expenses'));
    http
      .expectOne('/api/groups/g/expenses/e?expectedVersion=2')
      .flush(null, { status: 204, statusText: 'No Content' });
    http
      .expectOne('/api/dashboard')
      .flush({ groups: [], people: [], settlements: [], activity: [], totals: [] });
    harness.detectChanges();
    flushGroup([]);
    harness.detectChanges();
    expect(button('Deleted expenses')).toBeDefined();
    button('Deleted expenses')!.click();
    http
      .expectOne('/api/groups/g/expenses/deleted')
      .flush([{ expense: { ...snapshot, version: 3 }, deletedAt: '2026-10-25T14:00:00Z' }]);
    harness.detectChanges();
    const recovery = harness.routeNativeElement!.querySelector('[aria-label="Deleted expenses"]')!;
    expect(recovery.textContent).toContain('Dinner');
    expect(recovery.textContent).toContain('Bob Payer');
    expect(recovery.textContent).toContain('1.234');
    expect(recovery.textContent).toContain('Weights');
    expect(recovery.textContent).toContain('changes balances');
    button('Restore expense')!.click();
    harness.detectChanges();
    const restoration = http.expectOne({ method: 'POST', url: '/api/groups/g/expenses/e/restore' });
    expect(restoration.request.body.expectedVersion).toBe(3);
    expect(button('Restore expense')!.disabled).toBe(true);
    button('Restore expense')!.click();
    http.expectNone((request) => request.method === 'POST');
    restoration.flush({ ...snapshot, version: 4 });
    http
      .expectOne('/api/dashboard')
      .flush({ groups: [], people: [], settlements: [], activity: [], totals: [] });
    harness.detectChanges();
    flushGroup([{ ...summary, version: 4 }]);
    http.expectOne('/api/groups/g/expenses/deleted').flush([]);
    harness.detectChanges();
    expect(harness.routeNativeElement!.querySelectorAll('.xrow')).toHaveLength(1);
    expect(recovery.textContent).not.toContain('Dinner');
    expect(harness.routeNativeElement!.textContent).toContain('Expense restored');
    http.verify();
  });

  it('requires explicit read review after an uncertain restore and reloads balances and live expenses', async () => {
    await open([]);
    button('Deleted expenses')!.click();
    http
      .expectOne('/api/groups/g/expenses/deleted')
      .flush([{ expense: { ...snapshot, version: 3 }, deletedAt: '2026-10-25T14:00:00Z' }]);
    harness.detectChanges();
    button('Restore expense')!.click();
    http.expectOne('/api/groups/g/expenses/e/restore').error(new ProgressEvent('error'));
    harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain('Could not confirm restoration');
    expect(harness.routeNativeElement!.textContent).toContain('review current balances');
    expect(button('Restore expense')!.disabled).toBe(true);
    http.expectNone((request) => request.method === 'POST');
    http.expectNone((request) => request.method === 'GET');
    TestBed.inject(DashboardService).revalidate();
    http
      .expectOne('/api/dashboard')
      .flush({ groups: [], people: [], settlements: [], activity: [], totals: [] });
    flushGroup([{ ...summary, version: 4 }]);
    http.expectOne('/api/groups/g/expenses/deleted').flush([
      {
        expense: { ...snapshot, id: 'other', description: 'Another deleted expense', version: 3 },
        deletedAt: '2026-10-25T14:00:00Z',
      },
    ]);
    harness.detectChanges();
    expect(button('Restore expense')!.disabled).toBe(true);
    expect(harness.routeNativeElement!.textContent).toContain('Could not confirm restoration');
    http.expectNone((request) => request.method === 'POST');
    button('Reload deleted expenses')!.click();
    http
      .expectOne('/api/dashboard')
      .flush({ groups: [], people: [], settlements: [], activity: [], totals: [] });
    harness.detectChanges();
    flushGroup([{ ...summary, version: 4 }]);
    http.expectOne('/api/groups/g/expenses/deleted').flush([]);
    harness.detectChanges();
    expect(harness.routeNativeElement!.querySelectorAll('.xrow')).toHaveLength(1);
    expect(button('Restore expense')).toBeUndefined();
    http.expectNone((request) => request.method === 'POST');
    http.verify();
  });

  it.each([
    ['history', 'account'],
    ['recovery', 'route'],
    ['restore', 'account'],
    ['restore', 'route'],
  ])('cancels pending %s and clears earlier private facts on %s change', async (kind, boundary) => {
    await open();
    let pending;
    if (kind === 'history') {
      (harness.routeNativeElement!.querySelector('.xrow') as HTMLButtonElement).click();
      http.expectOne('/api/groups/g/expenses/e').flush(snapshot);
      harness.detectChanges();
      button('Expense history')!.click();
      pending = http.expectOne('/api/groups/g/expenses/e/history');
    } else {
      button('Deleted expenses')!.click();
      pending = http.expectOne('/api/groups/g/expenses/deleted');
      if (kind === 'restore') {
        pending.flush([
          {
            expense: { ...snapshot, version: 3, description: 'Earlier private expense' },
            deletedAt: '2026-10-25T14:00:00Z',
          },
        ]);
        harness.detectChanges();
        button('Restore expense')!.click();
        pending = http.expectOne('/api/groups/g/expenses/e/restore');
      }
    }
    if (boundary === 'account') {
      storeTestSession(bob);
    } else {
      await harness.navigateByUrl('/groups/other');
      http
        .expectOne('/api/groups/other')
        .flush({ id: 'other', name: 'Other group', type: 'HOME', members: [alice] });
      http.expectOne('/api/groups/other/expenses').flush([]);
      http.expectOne('/api/groups/other/balances/mine').flush([]);
      http.expectOne('/api/groups/other/balances').flush({ balances: [] });
    }
    harness.detectChanges();
    expect(pending.cancelled).toBe(true);
    expect(harness.routeNativeElement!.textContent).not.toContain('Earlier private expense');
    expect(harness.routeNativeElement!.textContent).not.toContain('Dinner');
    expect(harness.routeNativeElement!.querySelector('[aria-label="Expense history"]')).toBeNull();
    expect(harness.routeNativeElement!.querySelector('[aria-label="Deleted expenses"]')).toBeNull();
    http.expectNone((request) => request.method === 'POST');
    http.verify();
  });

  it.each([
    ['PERCENTAGE', 5000, '50%'],
    ['UNEQUAL', 617, '0.617'],
    ['ADJUSTMENTS', -1, '−KWD'],
  ])(
    'renders imported %s inputs in user units without inventing a recorder',
    async (splitType, value, expected) => {
      await open();
      (harness.routeNativeElement!.querySelector('.xrow') as HTMLButtonElement).click();
      http.expectOne('/api/groups/g/expenses/e').flush(snapshot);
      harness.detectChanges();
      button('Expense history')!.click();
      http.expectOne('/api/groups/g/expenses/e/history').flush([
        {
          action: 'IMPORTED',
          actor: null,
          recordedAt: '2026-10-25T12:00:00Z',
          version: 2,
          before: null,
          after: {
            ...snapshot,
            splitType,
            splits: [
              { userId: alice.id, value },
              { userId: bob.id, value: splitType === 'ADJUSTMENTS' ? 1 : value },
            ],
          },
        },
      ]);
      harness.detectChanges();
      const region = harness.routeNativeElement!.querySelector('[aria-label="Expense history"]')!;
      expect(region.textContent).toContain('Imported baseline');
      expect(region.textContent).toContain('Recorder unknown');
      expect(region.textContent).toContain('Earlier recorder and edits are unknown');
      const inputs = region.querySelector('[aria-label="Original split inputs"]')!;
      expect(inputs).not.toBeNull();
      expect(inputs.textContent).toContain(expected);
      expect(inputs.textContent).toContain('Alice Recorder:');
      http.verify();
    },
  );

  it.each([true, false])(
    'foreground revalidation refreshes open history and recovery panels (active=%s)',
    async (active) => {
      await open(active ? [summary] : []);
      if (active) {
        (harness.routeNativeElement!.querySelector('.xrow') as HTMLButtonElement).click();
        http.expectOne('/api/groups/g/expenses/e').flush(snapshot);
        harness.detectChanges();
      }
      button('Deleted expenses')!.click();
      http
        .expectOne('/api/groups/g/expenses/deleted')
        .flush(
          active
            ? []
            : [{ expense: { ...snapshot, version: 3 }, deletedAt: '2026-10-25T14:00:00Z' }],
        );
      harness.detectChanges();
      button('Expense history')!.click();
      http.expectOne('/api/groups/g/expenses/e/history').flush([
        {
          action: 'CREATED',
          actor: alice,
          recordedAt: '2026-10-25T12:00:00Z',
          version: 0,
          before: null,
          after: snapshot,
        },
      ]);
      harness.detectChanges();
      TestBed.inject(DashboardService).revalidate();
      const history = http.expectOne('/api/groups/g/expenses/e/history');
      const recovery = http.expectOne('/api/groups/g/expenses/deleted');
      http
        .expectOne('/api/dashboard')
        .flush({ groups: [], people: [], settlements: [], activity: [], totals: [] });
      flushGroup([{ ...summary, version: 4, description: 'Updated elsewhere', amountMinor: 1501 }]);
      if (active)
        http
          .expectOne('/api/groups/g/expenses/e')
          .flush({ ...snapshot, version: 4, description: 'Updated elsewhere', amountMinor: 1501 });
      history.flush([
        {
          action: active ? 'REPLACED' : 'RESTORED',
          actor: bob,
          recordedAt: '2026-10-25T15:00:00Z',
          version: 4,
          before: snapshot,
          after: { ...snapshot, description: 'Updated elsewhere', amountMinor: 1501 },
        },
      ]);
      recovery.flush([]);
      harness.detectChanges();
      const region = harness.routeNativeElement!.querySelector('[aria-label="Expense history"]')!;
      expect(region).not.toBeNull();
      expect(region.textContent).toContain(active ? 'Replaced' : 'Restored');
      expect(region.textContent).toContain('Updated elsewhere');
      expect(
        harness.routeNativeElement!.querySelector('[aria-label="Deleted expenses"]')!.textContent,
      ).toContain('No deleted expenses');
      http.verify();
    },
  );

  it('keeps conflict guidance visible and uses a fresh reviewed version for the next explicit restore', async () => {
    await open([]);
    button('Deleted expenses')!.click();
    http
      .expectOne('/api/groups/g/expenses/deleted')
      .flush([{ expense: { ...snapshot, version: 3 }, deletedAt: '2026-10-25T14:00:00Z' }]);
    harness.detectChanges();
    button('Restore expense')!.click();
    http
      .expectOne('/api/groups/g/expenses/e/restore')
      .flush(
        { message: 'This expense changed. Reload and review the latest changes.' },
        { status: 409, statusText: 'Conflict' },
      );
    harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain(
      'Reload and review the latest changes',
    );
    expect(harness.routeNativeElement!.textContent).toContain('Dinner');
    expect(button('Restore expense')!.disabled).toBe(true);
    http.expectNone((request) => request.method === 'POST');
    button('Reload deleted expenses')!.click();
    http
      .expectOne('/api/dashboard')
      .flush({ groups: [], people: [], settlements: [], activity: [], totals: [] });
    flushGroup([]);
    http
      .expectOne('/api/groups/g/expenses/deleted')
      .flush([{ expense: { ...snapshot, version: 5 }, deletedAt: '2026-10-25T15:00:00Z' }]);
    harness.detectChanges();
    expect(button('Restore expense')!.disabled).toBe(false);
    button('Restore expense')!.click();
    const restoration = http.expectOne('/api/groups/g/expenses/e/restore');
    expect(restoration.request.body.expectedVersion).toBe(5);
    restoration.flush(
      { message: 'Re-add former participants before changing this expense.' },
      { status: 409, statusText: 'Conflict' },
    );
    harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain('Re-add former participants');
    expect(button('Restore expense')!.disabled).toBe(true);
    http.verify();
  });
});
