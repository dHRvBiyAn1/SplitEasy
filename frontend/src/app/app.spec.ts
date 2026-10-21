import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AccountActionComponent } from './auth/account-action.component';
import { App } from './app';
import { storeTestSession } from './core/auth/auth-test-fixture';

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
        provideRouter([{ path: 'account-action', component: AccountActionComponent }]),
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
    storeTestSession({ id: 'u2', email: 'b@example.test', displayName: 'Blair' });
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: 'spliteasy.user',
        storageArea: localStorage,
        newValue: localStorage.getItem('spliteasy.user'),
      }),
    );
    fixture.detectChanges();
    httpTesting.expectOne('/api/me').flush({ id: 'u2', displayName: 'Blair' });
    httpTesting
      .expectOne('/api/dashboard')
      .flush({ groups: [], people: [], settlements: [], activity: [] });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.account-name')?.textContent).toContain('Blair');
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
