import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { GroupDetailComponent } from './group-detail.component';
import { DashboardService } from '../dashboard/dashboard.service';
import { SessionService } from '../core/auth/session.service';

const user = { id: 'me', displayName: 'Ada', email: 'ada@example.test' };
const expense = {
  id: 'e',
  version: 0,
  description: 'Dinner',
  amountMinor: 2000,
  currency: 'USD' as const,
  paidBy: user,
  category: 'FOOD_DRINK',
  spentOn: '2026-10-16',
  viewerDeltaMinor: 1000,
  participants: [{ user, shareMinor: 2000 }],
};
const secondExpense = { ...expense, id: 'second', description: 'Lunch' };
describe('visible expense failures', () => {
  let http: HttpTestingController;
  const params = new BehaviorSubject(convertToParamMap({ id: 'a' }));
  beforeEach(() => {
    params.next(convertToParamMap({ id: 'a' }));
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: params } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  function open(items = [expense]) {
    const fixture = TestBed.createComponent(GroupDetailComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/a').flush({ id: 'a', name: 'Home', type: 'HOME', members: [user] });
    http.expectOne('/api/groups/a/balances/mine').flush([]);
    http.expectOne('/api/groups/a/balances').flush({ balances: [] });
    http.expectOne('/api/groups/a/expenses').flush(items);
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.xrow') as HTMLButtonElement).click();
    return fixture;
  }
  it('refreshes the selected expense independently when the overview fails', () => {
    const fixture = open();
    http.expectOne('/api/groups/a/expenses/e').flush(expense);
    fixture.detectChanges();
    TestBed.inject(DashboardService).revalidate();
    http.expectOne('/api/dashboard').flush({}, { status: 503, statusText: 'Unavailable' });
    http.expectOne('/api/groups/a').flush({ id: 'a', name: 'Home', type: 'HOME', members: [user] });
    http.expectOne('/api/groups/a/balances/mine').flush([{ user, netMinor: 3000 }]);
    http.expectOne('/api/groups/a/balances').flush({ balances: [] });
    http.expectOne('/api/groups/a/expenses').flush([{ ...expense, amountMinor: 6000 }]);
    http.expectOne('/api/groups/a/expenses/e').flush({ ...expense, amountMinor: 6000 });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('$60.00');
    expect(fixture.nativeElement.querySelector('.xrow')?.getAttribute('aria-expanded')).toBe(
      'true',
    );
    expect(fixture.nativeElement.textContent).toContain('Delete expense');
  });
  it('keeps confirmed group money and actionable detail errors visible after a failed refresh', () => {
    const fixture = open();
    http
      .expectOne('/api/groups/a/expenses/e')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    TestBed.inject(DashboardService).revalidate();
    const overview = http.expectOne('/api/dashboard');
    const group = http.expectOne('/api/groups/a');
    const balances = http.expectOne('/api/groups/a/balances/mine');
    const ledger = http.expectOne('/api/groups/a/balances');
    const expenses = http.expectOne('/api/groups/a/expenses');
    group.flush({}, { status: 503, statusText: 'Unavailable' });
    overview.flush({}, { status: 503, statusText: 'Unavailable' });
    expect(balances.cancelled).toBe(true);
    expect(ledger.cancelled).toBe(true);
    expect(expenses.cancelled).toBe(true);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Showing the last loaded group');
    expect(fixture.nativeElement.textContent).toContain('Dinner');
    expect(fixture.nativeElement.textContent).toContain('Could not load this expense.');
    expect(fixture.nativeElement.querySelector('.gd__actions .dc-btn-outline')?.disabled).toBe(
      true,
    );
  });
  it('keeps the selected receipt and its actionable detail error through an explicit group read retry', () => {
    const fixture = open();
    http
      .expectOne('/api/groups/a/expenses/e')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    TestBed.inject(DashboardService).revalidate();
    http.expectOne('/api/dashboard').flush({}, { status: 503, statusText: 'Unavailable' });
    const group = http.expectOne('/api/groups/a');
    http.expectOne('/api/groups/a/balances/mine');
    http.expectOne('/api/groups/a/balances');
    http.expectOne('/api/groups/a/expenses');
    group.flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    const retry = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((button) => button.textContent?.trim() === 'Try again')!;
    retry.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.xrow')?.getAttribute('aria-expanded')).toBe(
      'true',
    );
    expect(fixture.nativeElement.textContent).toContain('Could not load this expense.');
    http.expectOne('/api/groups/a').flush({ id: 'a', name: 'Home', type: 'HOME', members: [user] });
    http.expectOne('/api/groups/a/balances/mine').flush([]);
    http.expectOne('/api/groups/a/balances').flush({ balances: [] });
    http.expectOne('/api/groups/a/expenses').flush([expense]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.xrow')?.getAttribute('aria-expanded')).toBe(
      'true',
    );
    expect(fixture.nativeElement.textContent).toContain('Could not load this expense.');
    fixture.nativeElement.querySelector('.xdetail .dc-btn-outline').click();
    http.expectOne('/api/groups/a/expenses/e').flush(expense);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Delete expense');
    expect(fixture.nativeElement.textContent).not.toContain('Could not load this expense.');
  });
  it('hides previously loaded financial content when refreshed membership is denied', () => {
    const fixture = open();
    http.expectOne('/api/groups/a/expenses/e').flush(expense);
    fixture.detectChanges();
    TestBed.inject(DashboardService).revalidate();
    const group = http.expectOne('/api/groups/a');
    http.expectOne('/api/groups/a/balances/mine');
    http.expectOne('/api/groups/a/balances');
    http.expectOne('/api/groups/a/expenses');
    group.flush({}, { status: 403, statusText: 'Forbidden' });
    http.expectOne('/api/dashboard').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('You are not a member');
    expect(fixture.nativeElement.textContent).not.toContain('Dinner');
    expect(fixture.nativeElement.querySelector('.gd__pills')).toBeNull();
  });
  it('clears account-owned group history synchronously when a session ends', () => {
    const fixture = open();
    http.expectOne('/api/groups/a/expenses/e').flush(expense);
    fixture.detectChanges();
    TestBed.inject(DashboardService).revalidate();
    const overview = http.expectOne('/api/dashboard');
    const group = http.expectOne('/api/groups/a');
    const balances = http.expectOne('/api/groups/a/balances/mine');
    const ledger = http.expectOne('/api/groups/a/balances');
    const expenses = http.expectOne('/api/groups/a/expenses');
    TestBed.inject(SessionService).reset();
    expect([overview, group, balances, ledger, expenses].every((r) => r.cancelled)).toBe(true);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Dinner');
    expect(fixture.nativeElement.querySelector('.gd__pills')).toBeNull();
  });
  it('preserves a pending selected-expense read through failed foreground revalidation', () => {
    const fixture = open();
    const detail = http.expectOne('/api/groups/a/expenses/e');
    TestBed.inject(DashboardService).revalidate();
    const overview = http.expectOne('/api/dashboard');
    const group = http.expectOne('/api/groups/a');
    http.expectOne('/api/groups/a/balances/mine');
    http.expectOne('/api/groups/a/balances');
    http.expectOne('/api/groups/a/expenses');
    expect(detail.cancelled).toBe(false);
    group.flush({}, { status: 503, statusText: 'Unavailable' });
    overview.flush({}, { status: 503, statusText: 'Unavailable' });
    detail.flush(expense);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Showing the last loaded group');
    expect(fixture.nativeElement.textContent).toContain('Delete expense');
    expect(fixture.nativeElement.querySelector('[aria-label="Loading details"]')).toBeNull();
  });
  it('keeps write-invalidated detail collapsed after failed group refresh and allows re-expansion', () => {
    const fixture = open();
    const detail = http.expectOne('/api/groups/a/expenses/e');
    TestBed.inject(DashboardService).refresh();
    const overview = http.expectOne('/api/dashboard');
    const group = http.expectOne('/api/groups/a');
    http.expectOne('/api/groups/a/balances/mine');
    http.expectOne('/api/groups/a/balances');
    http.expectOne('/api/groups/a/expenses');
    expect(detail.cancelled).toBe(true);
    group.flush({}, { status: 503, statusText: 'Unavailable' });
    overview.flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[aria-label="Loading details"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('.xdetail')).toBeNull();
    fixture.nativeElement.querySelector('.xrow').click();
    http.expectOne('/api/groups/a/expenses/e').flush(expense);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Delete expense');
  });
  it('clears retained expense feedback across group route generations A to B to A', () => {
    const fixture = open();
    http.expectOne('/api/groups/a/expenses/e').flush(expense);
    fixture.detectChanges();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    fixture.nativeElement.querySelector('.xdetail__del').click();
    http
      .expectOne(
        (request) =>
          request.method === 'DELETE' && request.url.split('?')[0] === '/api/groups/a/expenses/e',
      )
      .flush({ message: 'Old deletion conflict' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Old deletion conflict');
    params.next(convertToParamMap({ id: 'b' }));
    http.expectOne('/api/groups/b').flush({ id: 'b', name: 'Trip', type: 'TRIP', members: [user] });
    http.expectOne('/api/groups/b/balances/mine').flush([]);
    http.expectOne('/api/groups/b/balances').flush({ balances: [] });
    http.expectOne('/api/groups/b/expenses').flush([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Old deletion conflict');
    params.next(convertToParamMap({ id: 'a' }));
    http.expectOne('/api/groups/a').flush({ id: 'a', name: 'Home', type: 'HOME', members: [user] });
    http.expectOne('/api/groups/a/balances/mine').flush([]);
    http.expectOne('/api/groups/a/balances').flush({ balances: [] });
    http.expectOne('/api/groups/a/expenses').flush([expense]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Dinner');
    expect(fixture.nativeElement.querySelector('[role=alert]')).toBeNull();
  });
  it('offers an explicit current-expense retry after detail failure', () => {
    const fixture = open();
    http
      .expectOne('/api/groups/a/expenses/e')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]')?.textContent).toContain(
      'Could not load this expense.',
    );
    (fixture.nativeElement.querySelector('.xdetail .dc-btn-outline') as HTMLButtonElement).click();
    http.expectOne('/api/groups/a/expenses/e').flush(expense);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Delete expense');
  });
  it('keeps the expense and selection when deletion conflicts and shows lifecycle guidance', () => {
    const fixture = open();
    http.expectOne('/api/groups/a/expenses/e').flush(expense);
    fixture.detectChanges();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    (fixture.nativeElement.querySelector('.xdetail__del') as HTMLButtonElement).click();
    http
      .expectOne({ method: 'DELETE', url: '/api/groups/a/expenses/e?expectedVersion=0' })
      .flush(
        { message: 'Re-add former participants before changing this expense.' },
        { status: 409, statusText: 'Conflict' },
      );
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role=alert]')?.textContent).toContain(
      'Re-add former participants',
    );
    expect(fixture.nativeElement.querySelector('.xrow')?.getAttribute('aria-expanded')).toBe(
      'true',
    );
    expect(fixture.nativeElement.textContent).toContain('Dinner');
  });
  it('keeps delayed deletion guidance visible on its expense after collapsing it', () => {
    const fixture = open();
    http.expectOne('/api/groups/a/expenses/e').flush(expense);
    fixture.detectChanges();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    (fixture.nativeElement.querySelector('.xdetail__del') as HTMLButtonElement).click();
    const deletion = http.expectOne({
      method: 'DELETE',
      url: '/api/groups/a/expenses/e?expectedVersion=0',
    });
    (fixture.nativeElement.querySelector('.xrow') as HTMLButtonElement).click();
    fixture.detectChanges();
    deletion.flush(
      { message: 'Re-add former participants before changing this expense.' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    const row = fixture.nativeElement.querySelector('.xitem');
    expect(row.querySelector('.xrow').getAttribute('aria-expanded')).toBe('false');
    expect(row.querySelector('[role=alert]')?.textContent).toContain('Re-add former participants');
    (row.querySelector('.xrow') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(row.querySelector('[role=alert]')?.textContent).toContain('Re-add former participants');
  });
  it('keeps delayed deletion guidance on Dinner when Lunch is expanded', () => {
    const fixture = open([expense, secondExpense]);
    http.expectOne('/api/groups/a/expenses/e').flush(expense);
    fixture.detectChanges();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    (fixture.nativeElement.querySelector('.xdetail__del') as HTMLButtonElement).click();
    const deletion = http.expectOne({
      method: 'DELETE',
      url: '/api/groups/a/expenses/e?expectedVersion=0',
    });
    (fixture.nativeElement.querySelectorAll('.xrow')[1] as HTMLButtonElement).click();
    http.expectOne('/api/groups/a/expenses/second').flush(secondExpense);
    deletion.flush(
      { message: 'Re-add former participants before changing this expense.' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    const rows = fixture.nativeElement.querySelectorAll('.xitem');
    expect(rows[0].querySelector('[role=alert]')?.textContent).toContain('Dinner');
    expect(rows[0].querySelector('[role=alert]')?.textContent).toContain(
      'Re-add former participants',
    );
    expect(rows[1].querySelector('.xrow').getAttribute('aria-expanded')).toBe('true');
    expect(rows[1].querySelector('[role=alert]')).toBeNull();
  });
  it('refreshes the rendered expenses after successful deletion', () => {
    const fixture = open([expense, secondExpense]);
    http.expectOne('/api/groups/a/expenses/e').flush(expense);
    fixture.detectChanges();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    (fixture.nativeElement.querySelector('.xdetail__del') as HTMLButtonElement).click();
    http
      .expectOne({ method: 'DELETE', url: '/api/groups/a/expenses/e?expectedVersion=0' })
      .flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/dashboard').flush({
      totalNetMinor: 0,
      owedMinor: 0,
      owedPeopleCount: 0,
      oweMinor: 0,
      owePeopleCount: 0,
      groupCount: 1,
      groups: [],
      people: [],
      settlements: [],
      activity: [],
    });
    fixture.detectChanges();
    http.expectOne('/api/groups/a').flush({ id: 'a', name: 'Home', type: 'HOME', members: [user] });
    http.expectOne('/api/groups/a/balances/mine').flush([]);
    http.expectOne('/api/groups/a/balances').flush({ balances: [] });
    http.expectOne('/api/groups/a/expenses').flush([secondExpense]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Dinner');
    expect(fixture.nativeElement.textContent).toContain('Lunch');
    expect(fixture.nativeElement.querySelectorAll('.xrow')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('[role=alert]')).toBeNull();
  });
  it('exposes the full financial date to assistive technology', () => {
    const fixture = open();
    http.expectOne('/api/groups/a/expenses/e').flush(expense);
    fixture.detectChanges();
    const date = fixture.nativeElement.querySelector('time');
    expect(date?.getAttribute('datetime')).toBe('2026-10-16');
    expect(date?.getAttribute('aria-label')).toBe('October 16, 2026');
    expect(date?.getAttribute('aria-hidden')).not.toBe('true');
  });
  it('cancels A detail ownership before B can render or retry', () => {
    const fixture = open();
    const old = http.expectOne('/api/groups/a/expenses/e');
    params.next(convertToParamMap({ id: 'b' }));
    expect(old.cancelled).toBe(true);
    http.expectOne('/api/groups/b').flush({ id: 'b', name: 'Trip', type: 'TRIP', members: [user] });
    http.expectOne('/api/groups/b/balances/mine').flush([]);
    http.expectOne('/api/groups/b/balances').flush({ balances: [] });
    http.expectOne('/api/groups/b/expenses').flush([]);
    fixture.detectChanges();
    fixture.componentInstance.retryDetail();
    expect(fixture.nativeElement.textContent).toContain('Trip');
    expect(fixture.nativeElement.textContent).not.toContain('Dinner');
    expect(fixture.nativeElement.querySelector('[role=alert]')).toBeNull();
    http.expectNone('/api/groups/a/expenses/e');
  });
});
