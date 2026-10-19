import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { App } from './app';

const USER_KEY = 'spliteasy.user';
const TOKEN_KEY = 'spliteasy.accessToken';

function signIn(): void {
  localStorage.setItem(TOKEN_KEY, 'test-token');
  localStorage.setItem(
    USER_KEY,
    JSON.stringify({ id: 'u1', email: 'a@b.com', displayName: 'Alex Rivera' }),
  );
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
        provideRouter([{ path: 'elsewhere', children: [] }]),
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
});
