import { AuthService } from '../core/auth/auth.service';
import { ProfileService } from '../core/profile.service';
import { authInterceptor } from '../core/auth/auth.interceptor';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { routes } from '../app.routes';
import { LoginComponent } from './login.component';

describe('public account recovery', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter(routes),
      ],
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('focuses the invalid recovery email and explains the error without submitting', async () => {
    const route = routes.find((route) => route.path === 'forgot-password')!;
    const f = TestBed.createComponent((await route.loadComponent!()) as typeof LoginComponent);
    f.detectChanges();
    const email = f.nativeElement.querySelector('input[type=email]');
    f.nativeElement.querySelector('button[type=submit]').focus();
    f.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    f.detectChanges();
    expect(document.activeElement).toBe(email);
    expect(email.getAttribute('aria-invalid')).toBe('true');
    expect(f.nativeElement.querySelector('#recovery-email-hint')?.textContent).toContain(
      'valid email',
    );
    TestBed.inject(HttpTestingController).expectNone('/api/auth/password-reset-requests');
  });
  it('offers a public forgot-password route from login and gives the same delivery-neutral result', async () => {
    const login = TestBed.createComponent(LoginComponent);
    login.detectChanges();
    expect(login.nativeElement.querySelector('a[href="/forgot-password"]')).not.toBeNull();
    const route = routes.find((route) => route.path === 'forgot-password')!;
    expect(route.canActivate).toBeUndefined();
    const component = (await route.loadComponent!()) as typeof LoginComponent;
    const f = TestBed.createComponent(component);
    f.detectChanges();
    const email = f.nativeElement.querySelector('input[type=email]');
    email.value = 'alice@example.com';
    email.dispatchEvent(new Event('input'));
    f.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    const request = TestBed.inject(HttpTestingController).expectOne(
      '/api/auth/password-reset-requests',
    );
    expect(request.request.body).toEqual({ email: 'alice@example.com' });
    request.flush(null, { status: 202, statusText: 'Accepted' });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('If an account exists, a link may arrive');
  });
  it('shows explicit rate-limit and mail-unavailable feedback without retrying or losing the email', async () => {
    const route = routes.find((route) => route.path === 'forgot-password')!;
    const f = TestBed.createComponent((await route.loadComponent!()) as typeof LoginComponent);
    f.detectChanges();
    const email = f.nativeElement.querySelector('input[type=email]');
    email.value = 'alice@example.com';
    email.dispatchEvent(new Event('input'));
    const submit = () =>
      f.nativeElement
        .querySelector('form')
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    submit();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/auth/password-reset-requests')
      .flush({}, { status: 429, statusText: 'Limited', headers: { 'Retry-After': '60' } });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Too many attempts');
    expect(email.value).toBe('alice@example.com');
    submit();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/auth/password-reset-requests')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Email is temporarily unavailable');
  });

  it('focuses an invalid reset password and keeps its validation guidance available without submitting', async () => {
    history.replaceState({}, '', '/account-action#action=password-reset&token=' + 'a'.repeat(43));
    const route = routes.find((route) => route.path === 'account-action')!;
    const f = TestBed.createComponent((await route.loadComponent!()) as typeof LoginComponent);
    f.detectChanges();
    const password = f.nativeElement.querySelector('input[type=password]');
    f.nativeElement.querySelector('button[type=submit]').focus();
    f.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    f.detectChanges();
    expect(document.activeElement).toBe(password);
    expect(password.getAttribute('aria-invalid')).toBe('true');
    expect(f.nativeElement.querySelector('#reset-password-hint')?.textContent).toContain(
      '72 UTF-8 bytes',
    );
    TestBed.inject(HttpTestingController).expectNone('/api/auth/password-resets');
  });

  it('consumes the reset fragment immediately and changes the password only after explicit submit', async () => {
    history.replaceState({}, '', '/account-action#action=password-reset&token=' + 'a'.repeat(43));
    const route = routes.find((route) => route.path === 'account-action')!;
    expect(route).toBeDefined();
    expect(route.canActivate).toBeUndefined();
    const f = TestBed.createComponent((await route.loadComponent!()) as typeof LoginComponent);
    expect(location.hash).toBe('');
    f.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectNone('/api/auth/password-resets');
    const password = f.nativeElement.querySelector('input[type=password]');
    password.value = 'new-password';
    password.dispatchEvent(new Event('input'));
    f.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    const request = http.expectOne('/api/auth/password-resets');
    expect(request.request.body).toEqual({ token: 'a'.repeat(43), password: 'new-password' });
    request.flush(null, { status: 204, statusText: 'No Content' });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Password reset. Log in');
    expect(f.nativeElement.querySelector('input[type=password]')).toBeNull();
    expect(localStorage.length).toBe(0);
  });

  it('requires a valid single-use link and a password within the UTF-8 limit before sending anything', async () => {
    const route = routes.find((route) => route.path === 'account-action')!;
    history.replaceState({}, '', '/account-action#action=password-reset&token=' + 'a'.repeat(43));
    const f = TestBed.createComponent((await route.loadComponent!()) as typeof LoginComponent);
    f.detectChanges();
    const password = f.nativeElement.querySelector('input[type=password]');
    password.value = 'é'.repeat(37);
    password.dispatchEvent(new Event('input'));
    const submit = () =>
      f.nativeElement
        .querySelector('form')
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    submit();
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('72 UTF-8 bytes');
    TestBed.inject(HttpTestingController).expectNone('/api/auth/password-resets');
    password.value = 'short';
    password.dispatchEvent(new Event('input'));
    submit();
    f.detectChanges();
    expect(password.getAttribute('aria-invalid')).toBe('true');
    password.value = 'é'.repeat(36);
    password.dispatchEvent(new Event('input'));
    submit();
    const request = TestBed.inject(HttpTestingController).expectOne('/api/auth/password-resets');
    expect(request.request.body.password).toBe('é'.repeat(36));
    request.flush(
      { message: 'This link is invalid or expired. Request a new link.' },
      { status: 400, statusText: 'Bad Request' },
    );
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('This link is invalid or expired');
    f.destroy();
    history.replaceState({}, '', '/account-action#action=unknown&token=bad');
    const invalid = TestBed.createComponent(
      (await route.loadComponent!()) as typeof LoginComponent,
    );
    invalid.detectChanges();
    expect(location.hash).toBe('');
    expect(invalid.nativeElement.querySelector('form')).toBeNull();
    expect(invalid.nativeElement.textContent).toContain('Reopen the link');
  });

  it('preserves a mailbox reset draft and offers explicit retry feedback after rate limits or temporary failures', async () => {
    history.replaceState({}, '', '/account-action#action=password-reset&token=' + 'a'.repeat(43));
    const route = routes.find((route) => route.path === 'account-action')!;
    const f = TestBed.createComponent((await route.loadComponent!()) as typeof LoginComponent);
    f.detectChanges();
    const password = f.nativeElement.querySelector('input[type=password]');
    password.value = 'new-password';
    password.dispatchEvent(new Event('input'));
    const http = TestBed.inject(HttpTestingController);
    const submit = () =>
      f.nativeElement
        .querySelector('form')
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    submit();
    http
      .expectOne('/api/auth/password-resets')
      .flush({}, { status: 429, statusText: 'Limited', headers: { 'Retry-After': '60' } });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Too many attempts');
    expect(password.value).toBe('new-password');
    http.expectNone('/api/auth/password-resets');
    submit();
    const retry = http.expectOne('/api/auth/password-resets');
    expect(retry.request.body).toEqual({ token: 'a'.repeat(43), password: 'new-password' });
    retry.flush({}, { status: 503, statusText: 'Unavailable' });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Temporarily unavailable');
    expect(password.value).toBe('new-password');
    http.expectNone('/api/auth/password-resets');
  });

  it('requires confirmation before verifying an email and renders invalid/replaced link feedback', async () => {
    history.replaceState(
      {},
      '',
      '/account-action#action=email-verification&token=' + 'v'.repeat(43),
    );
    const route = routes.find((route) => route.path === 'account-action')!;
    const f = TestBed.createComponent((await route.loadComponent!()) as typeof LoginComponent);
    f.detectChanges();
    expect(location.hash).toBe('');
    expect(f.nativeElement.textContent).toContain('Verify email');
    expect(f.nativeElement.querySelector('input[type=password]')).toBeNull();
    const http = TestBed.inject(HttpTestingController);
    http.expectNone('/api/auth/email-verifications');
    const submit = () =>
      f.nativeElement
        .querySelector('form')
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    submit();
    const first = http.expectOne('/api/auth/email-verifications');
    expect(first.request.body).toEqual({ token: 'v'.repeat(43) });
    first.flush(
      { message: 'This link is invalid or expired. Request a new link.' },
      { status: 400, statusText: 'Bad Request' },
    );
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('This link is invalid or expired');
    submit();
    http
      .expectOne('/api/auth/email-verifications')
      .flush(null, { status: 204, statusText: 'No Content' });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Email verified');
    expect(f.nativeElement.querySelector('form')).toBeNull();
  });

  it('confirms an email change explicitly and tells the user to log in with the new email', async () => {
    history.replaceState({}, '', '/account-action#action=email-change&token=' + 'c'.repeat(43));
    const route = routes.find((route) => route.path === 'account-action')!;
    const f = TestBed.createComponent((await route.loadComponent!()) as typeof LoginComponent);
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Confirm email change');
    expect(f.nativeElement.querySelector('input')).toBeNull();
    const http = TestBed.inject(HttpTestingController);
    http.expectNone('/api/auth/email-change-confirmations');
    f.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    const request = http.expectOne('/api/auth/email-change-confirmations');
    expect(request.request.body).toEqual({ token: 'c'.repeat(43) });
    request.flush(null, { status: 204, statusText: 'No Content' });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Log in with your new email');
    expect(localStorage.length).toBe(0);
  });

  it('refreshes the signed-in profile after a mailbox action without marking a different account verified or logging it out', async () => {
    const auth = TestBed.inject(AuthService);
    const http = TestBed.inject(HttpTestingController);
    auth.login({ email: 'bob@example.com', password: 'password' }).subscribe();
    http.expectOne('/api/auth/login').flush({
      accessToken: 'bob-token',
      user: { id: 'bob', displayName: 'Bob', email: 'bob@example.com' },
    });
    history.replaceState(
      {},
      '',
      '/account-action#action=email-verification&token=' + 'a'.repeat(43),
    );
    const route = routes.find((route) => route.path === 'account-action')!;
    const f = TestBed.createComponent((await route.loadComponent!()) as typeof LoginComponent);
    f.detectChanges();
    const bob = {
      id: 'bob',
      displayName: 'Bob',
      email: 'bob@example.com',
      emailVerified: false,
      phone: '',
      preferredCurrency: 'USD',
      avatarColor: '#12A57E',
      createdAt: '2020-01-01T00:00:00Z',
    };
    http.expectOne('/api/me').flush(bob);
    f.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    http
      .expectOne('/api/auth/email-verifications')
      .flush(null, { status: 204, statusText: 'No Content' });
    const refresh = http.expectOne('/api/me');
    expect(refresh.request.headers.get('Authorization')).toBe('Bearer bob-token');
    refresh.flush(bob);
    f.detectChanges();
    expect(TestBed.inject(ProfileService).data()?.emailVerified).toBe(false);
    expect(auth.user()?.id).toBe('bob');
    expect(auth.getToken()).toBe('bob-token');
  });

  it('cancels a mailbox reset and clears secrets when a newer account signs in', async () => {
    history.replaceState({}, '', '/account-action#action=password-reset&token=' + 'a'.repeat(43));
    const route = routes.find((route) => route.path === 'account-action')!;
    const f = TestBed.createComponent((await route.loadComponent!()) as typeof LoginComponent);
    f.detectChanges();
    const password = f.nativeElement.querySelector('input[type=password]');
    password.value = 'new-password';
    password.dispatchEvent(new Event('input'));
    f.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    const http = TestBed.inject(HttpTestingController);
    const request = http.expectOne('/api/auth/password-resets');
    const auth = TestBed.inject(AuthService);
    auth.login({ email: 'bob@example.com', password: 'password' }).subscribe();
    http.expectOne('/api/auth/login').flush({
      accessToken: 'bob-token',
      user: { id: 'bob', displayName: 'Bob', email: 'bob@example.com' },
    });
    expect(request.cancelled).toBe(true);
    expect(password.value).toBe('');
    f.detectChanges();
    expect(f.nativeElement.querySelector('form')).toBeNull();
    expect(f.nativeElement.textContent).toContain('Reopen the link');
    http
      .expectOne('/api/me')
      .flush({ id: 'bob', displayName: 'Bob', email: 'bob@example.com', emailVerified: false });
    expect(auth.getToken()).toBe('bob-token');
  });
});
