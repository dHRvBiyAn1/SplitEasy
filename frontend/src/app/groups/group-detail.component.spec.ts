import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { GroupDetailComponent } from './group-detail.component';

const user = { id: 'me', displayName: 'Ada', email: 'ada@example.test' };
const expense = {
  id: 'e',
  description: 'Dinner',
  amountCents: 2000,
  paidBy: user,
  category: 'FOOD_DRINK',
  spentOn: '2026-10-16',
  viewerDeltaCents: 1000,
  participants: [{ user, shareCents: 2000 }],
};
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
  function open() {
    const fixture = TestBed.createComponent(GroupDetailComponent);
    fixture.detectChanges();
    http.expectOne('/api/groups/a').flush({ id: 'a', name: 'Home', type: 'HOME', members: [user] });
    http.expectOne('/api/groups/a/balances/mine').flush([]);
    http.expectOne('/api/groups/a/expenses').flush([expense]);
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.xrow') as HTMLButtonElement).click();
    return fixture;
  }
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
      .expectOne('/api/groups/a/expenses/e')
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
    http.expectOne('/api/groups/b/expenses').flush([]);
    fixture.detectChanges();
    fixture.componentInstance.retryDetail();
    expect(fixture.nativeElement.textContent).toContain('Trip');
    expect(fixture.nativeElement.textContent).not.toContain('Dinner');
    expect(fixture.nativeElement.querySelector('[role=alert]')).toBeNull();
    http.expectNone('/api/groups/a/expenses/e');
  });
});
