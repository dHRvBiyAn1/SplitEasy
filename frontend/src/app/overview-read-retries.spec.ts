import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { App } from './app';
import { DashboardComponent } from './dashboard/dashboard.component';
import { GroupDetailComponent } from './groups/group-detail.component';

const user = { id: 'me', displayName: 'Ada', email: 'ada@example.test' };
const expense = {
  id: 'e',
  description: 'Dinner',
  amountCents: 2000,
  paidBy: user,
  category: 'FOOD_DRINK',
  spentOn: '2026-10-16',
  viewerDeltaCents: 0,
  participants: [{ user, shareCents: 2000 }],
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

describe('overview read retry controls', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem(
      'spliteasy.accessToken',
      'e30.' + btoa(JSON.stringify({ sub: user.id })) + '.fixture',
    );
    localStorage.setItem('spliteasy.user', JSON.stringify(user));
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'a' })) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify();
    localStorage.clear();
  });
  function flushGroup(amountCents = 2000) {
    http.expectOne('/api/groups/a').flush({ id: 'a', name: 'Home', type: 'HOME', members: [user] });
    http.expectOne('/api/groups/a/balances/mine').flush([]);
    http.expectOne('/api/groups/a/expenses').flush([{ ...expense, amountCents }]);
  }
  it.each(['shell', 'dashboard'])(
    'keeps another rendered receipt selected while the %s overview retry reads',
    (control) => {
      const reader =
        control === 'shell'
          ? TestBed.createComponent(App)
          : TestBed.createComponent(DashboardComponent);
      reader.detectChanges();
      if (control === 'shell')
        http.expectOne('/api/me').flush({
          ...user,
          phone: '',
          avatarColor: '#12A57E',
          preferredCurrency: 'USD',
          createdAt: '2020-01-01T00:00:00Z',
        });
      const first = http.expectOne('/api/dashboard');
      if (control === 'shell') first.flush(overview);
      else first.flush({}, { status: 503, statusText: 'Unavailable' });
      const group = TestBed.createComponent(GroupDetailComponent);
      group.detectChanges();
      flushGroup();
      group.detectChanges();
      group.nativeElement.querySelector('.xrow').click();
      http.expectOne('/api/groups/a/expenses/e').flush(expense);
      group.detectChanges();
      if (control === 'shell') {
        window.dispatchEvent(new Event('focus'));
        http.expectOne('/api/dashboard').flush({}, { status: 503, statusText: 'Unavailable' });
        flushGroup();
        http.expectOne('/api/groups/a/expenses/e').flush(expense);
        group.detectChanges();
      }
      reader.detectChanges();
      reader.nativeElement
        .querySelector(control === 'shell' ? '.refresh-notice button' : '.empty-object button')
        .click();
      group.detectChanges();
      const expandedDuringRetry = group.nativeElement
        .querySelector('.xrow')
        ?.getAttribute('aria-expanded');
      const detailDuringRetry = group.nativeElement.textContent;
      http.expectOne('/api/dashboard').flush(overview);
      flushGroup(3000);
      const detailReads = http.match('/api/groups/a/expenses/e');
      detailReads.forEach((request) => request.flush({ ...expense, amountCents: 3000 }));
      group.detectChanges();
      reader.detectChanges();
      expect(expandedDuringRetry).toBe('true');
      expect(detailDuringRetry).toContain('Delete expense');
      expect(group.nativeElement.querySelector('.xrow')?.getAttribute('aria-expanded')).toBe(
        'true',
      );
      expect(group.nativeElement.querySelector('.xdetail__line--paid')?.textContent).toContain(
        '$30.00',
      );
    },
  );
});
