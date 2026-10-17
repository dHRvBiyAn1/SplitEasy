import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { GroupDetailComponent } from './group-detail.component';
import { GroupSettingsComponent } from './group-settings.component';
import { DashboardService } from '../dashboard/dashboard.service';

const user = { id: 'me', displayName: 'Ada', email: 'ada@example.test' };
const group = (id: string) => ({
  id,
  name: 'Group ' + id,
  type: 'HOME',
  createdBy: user,
  members: [user],
  simplifyDebts: true,
  createdAt: '',
});
const summary = {
  id: 'expense',
  description: 'Dinner',
  amountCents: 9000,
  paidBy: user,
  participantCount: 3,
  category: 'FOOD_DRINK',
  spentOn: '2026-10-16',
  viewerDeltaCents: 6000,
  createdAt: '',
};
const overview = {
  totalNetCents: 0,
  owedCents: 0,
  owedPeopleCount: 0,
  oweCents: 0,
  owePeopleCount: 0,
  groupCount: 0,
  groups: [],
  people: [],
  settlements: [],
  activity: [],
};

describe('group presentation ownership', () => {
  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let http: HttpTestingController;
  beforeEach(() => {
    params = new BehaviorSubject(convertToParamMap({ id: 'a' }));
    TestBed.configureTestingModule({
      imports: [GroupDetailComponent, GroupSettingsComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: params } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify({ ignoreCancelled: true }));
  it.each([GroupDetailComponent, GroupSettingsComponent])(
    'cancels prior group reads before rendering the next route (%s)',
    (component) => {
      const fixture = TestBed.createComponent<unknown>(component);
      fixture.detectChanges();
      const old = http.match((r) => r.url.startsWith('/api/groups/a'));
      params.next(convertToParamMap({ id: 'b' }));
      http.expectOne('/api/groups/b').flush(group('b'));
      if (component === GroupDetailComponent) {
        http.expectOne('/api/groups/b/balances/mine').flush([]);
        http.expectOne('/api/groups/b/expenses').flush([]);
      } else http.expectOne('/api/groups/b/balances').flush({ balances: [] });
      expect(old.length).toBeGreaterThan(0);
      expect(old.every((r) => r.cancelled)).toBe(true);
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain('Group b');
    },
  );
  it('clears an expanded receipt after a mutation refresh and distinguishes offsetting debts', () => {
    const fixture = TestBed.createComponent(GroupDetailComponent);
    fixture.detectChanges();
    http.match('/api/groups/a').forEach((r) => r.flush(group('a')));
    const balances = [
      { user: { ...user, id: 'noor' }, netCents: 5000 },
      { user: { ...user, id: 'luca' }, netCents: -5000 },
    ];
    http.match('/api/groups/a/balances/mine').forEach((r) => r.flush(balances));
    http.match('/api/groups/a/expenses').forEach((r) => r.flush([summary]));
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.xrow').click();
    http
      .expectOne('/api/groups/a/expenses/expense')
      .flush({ ...summary, groupId: 'a', participants: [], splitType: 'EQUAL' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.xdetail')).not.toBeNull();
    TestBed.inject(DashboardService).refresh();
    http.expectOne('/api/dashboard').flush(overview);
    fixture.detectChanges();
    http.match('/api/groups/a').forEach((r) => r.flush(group('a')));
    http.match('/api/groups/a/balances/mine').forEach((r) => r.flush(balances));
    http.match('/api/groups/a/expenses').forEach((r) => r.flush([summary]));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.xdetail')).toBeNull();
    expect(fixture.nativeElement.querySelector('.pill--overall').textContent).toContain(
      'Even overall',
    );
  });
});
