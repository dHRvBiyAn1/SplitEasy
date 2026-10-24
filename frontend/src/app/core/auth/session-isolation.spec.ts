import { testJwt } from './auth-test-fixture';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { vi } from 'vitest';
import { App } from '../../app';
import { DashboardResponse } from '../../dashboard/dashboard.models';
import { DashboardService } from '../../dashboard/dashboard.service';
import { GroupResponse } from '../../groups/group.models';
import { ModalService } from '../../modals/modal.service';
import { NewGroupModalComponent } from '../../modals/new-group-modal.component';
import { NewExpenseModalComponent } from '../../modals/new-expense-modal.component';
import { AddMemberModalComponent } from '../../modals/add-member-modal.component';
import { SettleUpModalComponent } from '../../modals/settle-up-modal.component';
import { AuthResponse } from './auth.models';
import { authInterceptor } from './auth.interceptor';
import { ProfileService } from '../profile.service';
import { AuthService } from './auth.service';

const alice: AuthResponse = {
  accessToken: testJwt('alice'),
  tokenType: 'Bearer',
  expiresIn: 3600,
  user: { id: 'alice', displayName: 'Alice', email: 'alice@example.com' },
};
const bob: AuthResponse = {
  ...alice,
  accessToken: 'bob-token',
  user: { id: 'bob', displayName: 'Bob', email: 'bob@example.com' },
};
const aliceDashboard: DashboardResponse = {
  totalNetMinor: 0,
  owedMinor: 0,
  owedPeopleCount: 0,
  oweMinor: 0,
  owePeopleCount: 0,
  groupCount: 1,
  groups: [
    {
      id: 'alice-group',
      name: 'Alice private group',
      type: 'HOME',
      memberCount: 1,
      totalSpentMinor: 0,
      youAreOwedMinor: 0,
      youOweMinor: 0,
      netMinor: 0,
      simplifyDebts: true,
    },
  ],
  people: [],
  settlements: [],
  activity: [],
};
const bobDashboard: DashboardResponse = {
  ...aliceDashboard,
  groups: [{ ...aliceDashboard.groups[0], id: 'bob-group', name: 'Bob private group' }],
};
const unauthorized = { status: 401, statusText: 'Unauthorized' };
const aliceGroup: GroupResponse = {
  id: 'alice-group',
  name: 'Alice private group',
  type: 'HOME',
  createdBy: alice.user,
  members: [alice.user],
  simplifyDebts: true,
  createdAt: '2026-10-15T00:00:00Z',
};

describe('account session isolation', () => {
  let requests: HttpTestingController;
  let http: HttpClient;
  let auth: AuthService;
  let dashboard: DashboardService;
  let modal: ModalService;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('spliteasy.accessToken', alice.accessToken);
    localStorage.setItem('spliteasy.user', JSON.stringify(alice.user));
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    requests = TestBed.inject(HttpTestingController);
    http = TestBed.inject(HttpClient);
    auth = TestBed.inject(AuthService);
    dashboard = TestBed.inject(DashboardService);
    modal = TestBed.inject(ModalService);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });

  afterEach(() => {
    try {
      // Standalone expense dialogs use USD while their optional profile read is unavailable.
      for (const request of requests.match('/api/me'))
        if (!request.cancelled) request.flush({}, { status: 503, statusText: 'Unavailable' });
      requests.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  function login(response: AuthResponse = bob): void {
    auth.login({ email: response.user.email, password: 'test-password' }).subscribe();
    requests.expectOne('/api/auth/login').flush(response);
  }

  function typeInto(element: HTMLElement, selector: string, value: string): void {
    const input = element.querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('cancels a pending group save before it can navigate or close Bob’s dialog', () => {
    modal.openGroup();
    const fixture = TestBed.createComponent(NewGroupModalComponent);
    fixture.detectChanges();
    typeInto(fixture.nativeElement, '#ng-name', 'Alice new group');
    fixture.componentInstance.save();
    const save = requests.expectOne('/api/groups');

    login();
    modal.openGroup();
    expect(save.cancelled).toBe(true);
    expect(modal.active()).toBe('group');
    expect(navigate).not.toHaveBeenCalled();
    requests.expectNone('/api/dashboard');
    fixture.destroy();
  });

  it('cancels the whole member-add sequence instead of sending remaining emails as Bob', () => {
    modal.openGroup();
    const fixture = TestBed.createComponent(NewGroupModalComponent);
    fixture.detectChanges();
    typeInto(fixture.nativeElement, '#ng-name', 'Alice new group');
    for (const email of ['first@example.com', 'second@example.com']) {
      typeInto(fixture.nativeElement, 'input[placeholder="friend@example.com"]', email);
      fixture.componentInstance.addEmail();
    }
    fixture.componentInstance.save();
    requests.expectOne('/api/groups').flush(aliceGroup);
    const first = requests.expectOne('/api/groups/alice-group/members');
    expect(first.request.body).toEqual({ email: 'first@example.com' });

    login();
    expect(first.cancelled).toBe(true);
    requests.expectNone('/api/groups/alice-group/members');
    requests.expectNone('/api/dashboard');
    expect(navigate).not.toHaveBeenCalled();
    fixture.destroy();
  });

  it.each(['member', 'expense'] as const)('cancels pending %s modal reads on logout', (kind) => {
    if (kind === 'member') modal.openAddMember('alice-group');
    else modal.openExpense('alice-group');
    const components = {
      member: AddMemberModalComponent,
      expense: NewExpenseModalComponent,
      settle: SettleUpModalComponent,
    };
    const fixture = TestBed.createComponent<unknown>(components[kind]);
    fixture.detectChanges();
    const read = requests.expectOne('/api/groups/alice-group');

    auth.logout();
    expect(read.cancelled).toBe(true);
    expect(modal.active()).toBeNull();
    fixture.destroy();
  });

  it('fetches and renders Bob’s shell after Alice’s protected request expires', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    requests.expectOne('/api/dashboard').flush(aliceDashboard);
    requests.expectOne('/api/me').flush({
      ...alice.user,
      phone: '',
      avatarColor: '#12A57E',
      preferredCurrency: 'USD',
      createdAt: '2020-01-01T00:00:00Z',
    });
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.mobile-nav [data-nav=groups]').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Alice private group');

    http.get('/api/groups').subscribe({ error: () => {} });
    requests.expectOne('/api/groups').flush({}, unauthorized);
    fixture.detectChanges();
    expect(auth.user()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login']);
    expect(fixture.nativeElement.textContent).not.toContain('Alice private group');

    login();
    fixture.detectChanges();
    requests.expectOne('/api/me').flush({
      ...bob.user,
      phone: '',
      avatarColor: '#12A57E',
      preferredCurrency: 'USD',
      createdAt: '2020-01-01T00:00:00Z',
    });
    const bobRequest = requests.expectOne('/api/dashboard');
    expect(bobRequest.request.headers.get('Authorization')).toBe('Bearer bob-token');
    bobRequest.flush(bobDashboard);
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.mobile-nav [data-nav=groups]').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Bob private group');
    expect(fixture.nativeElement.textContent).not.toContain('Alice private group');
    fixture.destroy();
  });

  it.each(['logout', 'login', 'register', '401'])(
    'clears cached data and every account-modal context on %s',
    (transition) => {
      dashboard.ensureLoaded();
      requests.expectOne('/api/dashboard').flush(aliceDashboard);
      modal.openExpense('alice-group', 'alice-expense');
      modal.openSettle(
        {
          groupId: 'alice-group',
          groupName: 'Alice private group',
          counterparty: alice.user,
          netMinor: 100,
        },
        'alice-group',
      );
      modal.openAddMember('alice-group');
      modal.openGroup();

      if (transition === 'logout') {
        auth.logout();
      } else if (transition === 'login') {
        login();
      } else if (transition === 'register') {
        auth.register({ ...bob.user, password: 'test-password' }).subscribe();
        requests.expectOne('/api/auth/register').flush(bob);
      } else {
        http.get('/api/groups').subscribe({ error: () => {} });
        requests.expectOne('/api/groups').flush({}, unauthorized);
      }

      expect(dashboard.data()).toBeNull();
      expect(dashboard.loading()).toBe(false);
      expect(modal.active()).toBeNull();
      expect(modal.expenseGroupId()).toBeNull();
      expect(modal.editExpenseId()).toBeNull();
      expect(modal.settlePrefill()).toBeNull();
      expect(modal.settleGroupId()).toBeNull();
      expect(modal.addMemberGroupId()).toBeNull();
    },
  );

  it('unmounts the root account dialog when its session ends', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    requests.expectOne('/api/dashboard').flush(aliceDashboard);
    requests.expectOne('/api/me').flush({
      ...alice.user,
      phone: '',
      avatarColor: '#12A57E',
      preferredCurrency: 'USD',
      createdAt: '2020-01-01T00:00:00Z',
    });
    modal.openGroup();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-new-group-modal')).not.toBeNull();

    auth.logout();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-new-group-modal')).toBeNull();
    fixture.destroy();
  });

  it('ignores a late success after clear without finishing the new load', () => {
    dashboard.refresh();
    const oldRequest = requests.expectOne('/api/dashboard');
    dashboard.clear();
    expect(dashboard.loading()).toBe(false);
    dashboard.ensureLoaded();
    const newRequest = requests.expectOne('/api/dashboard');

    oldRequest.flush(aliceDashboard);
    expect(dashboard.data()).toBeNull();
    expect(dashboard.loading()).toBe(true);
    newRequest.flush(bobDashboard);
    expect(dashboard.data()).toEqual(bobDashboard);
    expect(dashboard.loading()).toBe(false);
  });

  it('does not refill a cleared cache when its outstanding request succeeds', () => {
    dashboard.refresh();
    const request = requests.expectOne('/api/dashboard');
    dashboard.clear();
    request.flush(aliceDashboard);
    expect(dashboard.data()).toBeNull();
    expect(dashboard.loading()).toBe(false);
  });

  it('ignores Alice’s late success after Bob has loaded his dashboard', () => {
    dashboard.refresh();
    const aliceRequest = requests.expectOne('/api/dashboard');
    login();
    dashboard.refresh();
    requests.expectOne('/api/dashboard').flush(bobDashboard);

    expect(aliceRequest.cancelled).toBe(true);
    expect(dashboard.data()).toEqual(bobDashboard);
    expect(auth.user()).toEqual(bob.user);
  });

  it.each([401, 500])('ignores Alice’s late dashboard %s while Bob is loading', (status) => {
    dashboard.refresh();
    const aliceRequest = requests.expectOne('/api/dashboard');
    auth.logout();
    login();
    dashboard.ensureLoaded();
    const bobRequest = requests.expectOne('/api/dashboard');

    expect(aliceRequest.cancelled, `old ${status} response must be cancelled`).toBe(true);
    expect(auth.user()).toEqual(bob.user);
    expect(dashboard.data()).toBeNull();
    expect(dashboard.loading()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
    bobRequest.flush(bobDashboard);
    expect(dashboard.data()).toEqual(bobDashboard);
  });

  it.each([401, 500])(
    'keeps Bob’s loaded data when Alice’s dashboard later fails with %s',
    (status) => {
      dashboard.refresh();
      const aliceRequest = requests.expectOne('/api/dashboard');
      login();
      dashboard.refresh();
      requests.expectOne('/api/dashboard').flush(bobDashboard);

      expect(aliceRequest.cancelled, `old ${status} response must be cancelled`).toBe(true);
      expect(auth.user()).toEqual(bob.user);
      expect(dashboard.data()).toEqual(bobDashboard);
      expect(dashboard.loading()).toBe(false);
      expect(navigate).not.toHaveBeenCalled();
    },
  );

  it.each([bob, alice])(
    'ignores an earlier session’s 401 after $user.displayName logs in',
    (next) => {
      http.get('/api/groups').subscribe({ error: () => {} });
      const oldRequest = requests.expectOne('/api/groups');
      login(next);
      modal.openGroup();

      oldRequest.flush({}, unauthorized);
      expect(auth.user()).toEqual(next.user);
      expect(auth.getToken()).toBe(next.accessToken);
      expect(modal.active()).toBe('group');
      expect(navigate).not.toHaveBeenCalled();
    },
  );

  it('handles concurrent current-session 401s with one logout and redirect', () => {
    http.get('/api/groups').subscribe({ error: () => {} });
    http.get('/api/dashboard').subscribe({ error: () => {} });
    const first = requests.expectOne('/api/groups');
    const second = requests.expectOne('/api/dashboard');
    first.flush({}, unauthorized);
    second.flush({}, unauthorized);
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.getToken()).toBeNull();
    expect(navigate).toHaveBeenCalledExactlyOnceWith(['/login']);
  });

  it('keeps the current session’s cache and modal when updating server preferences', () => {
    dashboard.ensureLoaded();
    requests.expectOne('/api/dashboard').flush(aliceDashboard);
    modal.openGroup();
    const profile = TestBed.inject(ProfileService);
    TestBed.tick();
    requests.expectOne('/api/me').flush({
      ...alice.user,
      phone: '',
      avatarColor: '#12A57E',
      preferredCurrency: 'USD',
      createdAt: '2020-01-01T00:00:00Z',
    });
    profile.update({ phone: '+1 234' });
    requests.expectOne('/api/me').flush({
      ...alice.user,
      phone: '+1 234',
      avatarColor: '#12A57E',
      preferredCurrency: 'USD',
      createdAt: '2020-01-01T00:00:00Z',
    });
    dashboard.ensureLoaded();
    requests.expectNone('/api/dashboard');
    expect(dashboard.data()).toEqual(aliceDashboard);
    expect(modal.active()).toBe('group');
  });

  it('deduplicates initial loads and accepts current-session success and failure', () => {
    dashboard.ensureLoaded();
    dashboard.ensureLoaded();
    expect(dashboard.loading()).toBe(true);
    requests.expectOne('/api/dashboard').flush(aliceDashboard);
    expect(dashboard.loading()).toBe(false);
    expect(dashboard.data()).toEqual(aliceDashboard);
    dashboard.ensureLoaded();
    requests.expectNone('/api/dashboard');

    dashboard.refresh();
    requests.expectOne('/api/dashboard').flush({}, { status: 500, statusText: 'Server error' });
    expect(dashboard.data()).toEqual(aliceDashboard);
    expect(dashboard.error()).toBe(true);
    expect(dashboard.loading()).toBe(false);
  });
});
