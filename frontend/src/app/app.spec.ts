import { provideHttpClient } from '@angular/common/http';
import { APP_BASE_HREF, BrowserPlatformLocation, PlatformLocation } from '@angular/common';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AccountActionComponent } from './auth/account-action.component';
import { App } from './app';
import { storeTestSession, testAuthResponse } from './core/auth/auth-test-fixture';
import { SessionService } from './core/auth/session.service';

function signIn(): void {
  storeTestSession({ id: 'u1', email: 'a@b.com', displayName: 'Alex Rivera' });
}

describe('App', () => {
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PlatformLocation, useClass: BrowserPlatformLocation },
        { provide: APP_BASE_HREF, useValue: '/' },
        provideRouter([
          { path: 'elsewhere', children: [] },
          { path: 'account-action', component: AccountActionComponent },
        ]),
      ],
    }).compileComponents();
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
    fixture.detectChanges();
    // Unauthenticated: no shell, no dashboard fetch.
    httpTesting.expectNone('/api/dashboard');
  });

  it('hides the navigation shell when unauthenticated', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.topbar')).toBeNull();
  });

  it('shows navigation and loads the dashboard when authenticated', () => {
    signIn();
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    httpTesting.expectOne('/api/me').flush({
      id: 'u1',
      email: 'a@b.com',
      displayName: 'Fresh Alex',
      phone: '',
      avatarColor: '#12A57E',
      preferredCurrency: 'USD',
      createdAt: '2020-01-01T00:00:00Z',
    });
    // The shell effect pulls the dashboard payload for the sidebar group balances.
    const req = httpTesting.expectOne('/api/dashboard');
    req.flush({
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
    });
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.brand')?.textContent).toContain('SplitEasy');
    expect(compiled.querySelector('.account-name')?.textContent).toContain('Fresh Alex');
  });
  it('refreshes confirmed balances on focus and visibility without duplicating a pending read', () => {
    signIn();
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    httpTesting.expectOne('/api/me').flush({
      id: 'u1',
      email: 'a@b.com',
      displayName: 'Alex',
      phone: '',
      avatarColor: '#12A57E',
      preferredCurrency: 'USD',
      createdAt: '2020-01-01T00:00:00Z',
    });
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
    httpTesting.expectOne('/api/dashboard').flush(overview);
    window.dispatchEvent(new Event('focus'));
    const refresh = httpTesting.expectOne('/api/dashboard');
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    window.dispatchEvent(new Event('focus'));
    httpTesting.expectNone('/api/dashboard');
    expect(refresh.cancelled).toBe(false);
    refresh.flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.refresh-notice')?.textContent).toContain(
      'last loaded overview',
    );
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    window.dispatchEvent(new Event('focus'));
    httpTesting.expectNone('/api/dashboard');
  });
  it('revalidates the cached overview after navigation', async () => {
    signIn();
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    httpTesting.expectOne('/api/me').flush({
      id: 'u1',
      email: 'a@b.com',
      displayName: 'Alex',
      phone: '',
      avatarColor: '#12A57E',
      preferredCurrency: 'USD',
      createdAt: '2020-01-01T00:00:00Z',
    });
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
    httpTesting.expectOne('/api/dashboard').flush(overview);
    await TestBed.inject(Router).navigateByUrl('/elsewhere');
    httpTesting.expectOne('/api/dashboard').flush(overview);
  });
  it('shows unavailable groups rather than an empty group claim after initial overview failure', () => {
    signIn();
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    httpTesting.expectOne('/api/me').flush({
      id: 'u1',
      email: 'a@b.com',
      displayName: 'Alex',
      phone: '',
      avatarColor: '#12A57E',
      preferredCurrency: 'USD',
      createdAt: '2020-01-01T00:00:00Z',
    });
    httpTesting.expectOne('/api/dashboard').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.nativeElement.querySelector('.mobile-nav [data-nav="groups"]').click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.group-tray')?.textContent).toContain(
      'Groups are unavailable',
    );
    expect(fixture.nativeElement.querySelector('.group-tray')?.textContent).not.toContain(
      'Start your first group',
    );
  });
  it('settles after an initial dashboard failure until the user retries', () => {
    signIn();
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    httpTesting.expectOne('/api/me').flush({
      id: 'u1',
      email: 'a@b.com',
      displayName: 'Alex Rivera',
      phone: '',
      avatarColor: '#12A57E',
      preferredCurrency: 'USD',
      createdAt: '2020-01-01T00:00:00Z',
    });
    httpTesting.expectOne('/api/dashboard').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    httpTesting.expectNone('/api/dashboard');
  });
  it('provides four labelled icon destinations and restores selection after an expense closes', () => {
    signIn();
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    httpTesting.expectOne('/api/me').flush({
      id: 'u1',
      email: 'a@b.com',
      displayName: 'Alex Rivera',
      phone: '',
      avatarColor: '#12A57E',
      preferredCurrency: 'USD',
      createdAt: '2020-01-01T00:00:00Z',
    });
    httpTesting.expectOne('/api/dashboard').flush({
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
    });
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;
    const dock = root.querySelector('.mobile-nav');
    expect(dock?.querySelectorAll('app-icon').length).toBe(4);
    const expense = dock?.querySelector<HTMLButtonElement>('[data-nav="expense"]');
    expect(expense).toBeTruthy();
    expense?.click();
    fixture.detectChanges();
    expect(expense?.getAttribute('aria-current')).toBe('page');
    root.querySelector<HTMLButtonElement>('.mdl-close')?.click();
    fixture.detectChanges();
    expect(expense?.getAttribute('aria-current')).toBeNull();
    expect(dock?.querySelector('[data-nav="overview"]')?.getAttribute('aria-current')).toBe('page');
  });
  it('loads the new account overview after a cross-tab switch without navigation or focus', () => {
    signIn();
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    httpTesting.expectOne('/api/me').flush({ id: 'u1', displayName: 'Alex Rivera' });
    httpTesting
      .expectOne('/api/dashboard')
      .flush({ groups: [], people: [], settlements: [], activity: [] });
    fixture.detectChanges();
    const replacement = { id: 'u2', email: 'b@example.test', displayName: 'Blair' };
    const marker = JSON.stringify({ id: 'remote-login', signedOut: false });
    localStorage.setItem('spliteasy.auth-change', marker);
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: 'spliteasy.auth-change',
        storageArea: localStorage,
        newValue: marker,
      }),
    );
    httpTesting.expectOne('/api/auth/refresh').flush(testAuthResponse(replacement));
    fixture.detectChanges();
    httpTesting.expectOne('/api/me').flush({ id: 'u2', displayName: 'Blair' });
    httpTesting
      .expectOne('/api/dashboard')
      .flush({ groups: [], people: [], settlements: [], activity: [] });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.account-name')?.textContent).toContain('Blair');
  });
  it('keeps a reopened mailbox token out of real browser history after Router navigation completes', async () => {
    history.replaceState({}, '', '/');
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    const first = '/account-action#action=password-reset&token=' + 'a'.repeat(43);
    history.replaceState({}, '', first);
    await router.navigateByUrl(first);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(location.hash).toBe('');
    TestBed.inject(SessionService).reset();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-account-action form')).toBeNull();
    const reopened = '/account-action#action=password-reset&token=' + 'b'.repeat(43);
    const oldURL = location.href;
    history.replaceState({}, '', reopened);
    window.dispatchEvent(new HashChangeEvent('hashchange', { oldURL, newURL: location.href }));
    expect(location.hash).toBe('');
    fixture.detectChanges();
    const password = fixture.nativeElement.querySelector('app-account-action input[type=password]');
    expect(password).not.toBeNull();
    password.value = 'new-password';
    password.dispatchEvent(new Event('input'));
    await router.navigateByUrl(reopened, { replaceUrl: true });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(location.hash).toBe('');
    expect(password.value).toBe('new-password');
    httpTesting.expectNone((request) => request.method === 'POST');
    fixture.nativeElement
      .querySelector('app-account-action form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    const request = httpTesting.expectOne('/api/auth/password-resets');
    expect(request.request.body).toEqual({ token: 'b'.repeat(43), password: 'new-password' });
    request.flush(null, { status: 204, statusText: 'No Content' });
    fixture.detectChanges();
    expect(password.value).toBe('');
    expect(fixture.nativeElement.textContent).toContain('Password reset. Log in');
    history.replaceState({}, '', '/');
  });

  it('retains an explicit mailbox confirmation when the signed-in shell changes to the public route', async () => {
    signIn();
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    httpTesting.expectOne('/api/me').flush({ id: 'u1', displayName: 'Alex Rivera' });
    httpTesting
      .expectOne('/api/dashboard')
      .flush({ groups: [], people: [], settlements: [], activity: [] });
    history.replaceState(
      {},
      '',
      '/account-action#action=email-verification&token=' + 'a'.repeat(43),
    );
    await TestBed.inject(Router).navigateByUrl(
      '/account-action#action=email-verification&token=' + 'a'.repeat(43),
    );
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const button = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((b) => b.textContent?.trim() === 'Verify email');
    expect(button).toBeTruthy();
    expect(location.hash).toBe('');
    httpTesting.expectNone((r) => r.method === 'POST');
    history.replaceState({}, '', '/');
  });
});
