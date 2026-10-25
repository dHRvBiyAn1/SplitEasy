import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { ApplicationInitStatus } from '@angular/core';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { appConfig } from '../../app.config';
import { LoginComponent } from '../../auth/login.component';
import { ProfileComponent } from '../../profile/profile.component';
import { authInterceptor } from './auth.interceptor';
import { SessionService } from './session.service';
import { AuthService } from './auth.service';
import { vi } from 'vitest';
import { testJwt } from './auth-test-fixture';

const alice = { id: 'alice', displayName: 'Alice', email: 'alice@example.test' };
const response = {
  accessToken: testJwt('alice'),
  sessionId: 'alice-device',
  tokenType: 'Bearer',
  expiresIn: 3600,
  user: alice,
};
const profile = {
  ...alice,
  emailVerified: false,
  phone: '',
  preferredCurrency: 'USD',
  avatarColor: '#12A57E',
  createdAt: '2020-01-01T00:00:00Z',
};

describe('cookie-backed browser session continuity', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('keeps an explicit local logout after a reload even when server sign-out failed', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    const previous = TestBed.inject(HttpTestingController);
    const auth = TestBed.inject(AuthService);
    auth.login({ email: alice.email, password: 'password123' }).subscribe();
    previous.expectOne('/api/auth/login').flush(response);
    auth.logout();
    previous.expectOne('/api/auth/logout').error(new ProgressEvent('error'));
    previous.verify();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    await TestBed.inject(ApplicationInitStatus).donePromise;
    http.expectNone('/api/auth/refresh');
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
    expect(TestBed.inject(AuthService).user()).toBeNull();
    expect(TestBed.inject(AuthService).getToken()).toBeNull();
  });

  it('cancels an old account write on a nonsecret tab marker and restores the replacement from its cookie', () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const auth = TestBed.inject(AuthService);
    auth.login({ email: alice.email, password: 'password123' }).subscribe();
    http.expectOne('/api/auth/login').flush(response);
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    http.expectOne('/api/me').flush(profile);
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.pcard__edit').click();
    fixture.detectChanges();
    const name = fixture.nativeElement.querySelector(
      'input[autocomplete=name]',
    ) as HTMLInputElement;
    name.value = 'Alice pending name';
    name.dispatchEvent(new Event('input'));
    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { cancelable: true }));
    const oldWrite = http.expectOne('/api/me');
    expect(oldWrite.request.headers.get('Authorization')).toBe('Bearer ' + response.accessToken);
    const marker = JSON.stringify({ id: 'remote-login', signedOut: false });
    localStorage.setItem('spliteasy.auth-change', marker);
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: 'spliteasy.auth-change',
        storageArea: localStorage,
        newValue: marker,
      }),
    );
    expect(oldWrite.cancelled).toBe(true);
    expect(auth.user()).toBeNull();
    const bob = { id: 'bob', email: 'bob@example.test', displayName: 'Bob' };
    http
      .expectOne('/api/auth/refresh')
      .flush({ ...response, user: bob, sessionId: 'bob-device', accessToken: testJwt('bob') });
    fixture.detectChanges();
    http.expectOne('/api/me').flush({ ...profile, ...bob });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(bob.email);
    expect(fixture.nativeElement.querySelector('input[autocomplete=name]')).toBeNull();
    http.expectNone('/api/auth/refresh');
    expect(localStorage.getItem('spliteasy.accessToken')).toBeNull();
    expect(localStorage.getItem('spliteasy.user')).toBeNull();
  });

  it.each([
    { user: { id: 'bob', email: 'bob@example.test', displayName: 'Bob' }, sessionId: 'bob-device' },
    { user: alice, sessionId: 'alice-replacement-device' },
  ])(
    'never replays an old write after renewal changes its account or device: $sessionId',
    ({ user, sessionId }) => {
      TestBed.configureTestingModule({
        providers: [
          provideHttpClient(withInterceptors([authInterceptor])),
          provideHttpClientTesting(),
          provideRouter([]),
        ],
      });
      const http = TestBed.inject(HttpTestingController);
      const auth = TestBed.inject(AuthService);
      auth.login({ email: alice.email, password: 'password123' }).subscribe();
      http.expectOne('/api/auth/login').flush(response);
      const fixture = TestBed.createComponent(ProfileComponent);
      fixture.detectChanges();
      http.expectOne('/api/me').flush(profile);
      fixture.detectChanges();
      fixture.nativeElement.querySelector('.pcard__edit').click();
      fixture.detectChanges();
      let failures = 0;
      TestBed.inject(HttpClient)
        .post('/api/groups', { name: 'Old private draft' })
        .subscribe({ error: () => failures++ });
      http.expectOne('/api/groups').flush({}, { status: 401, statusText: 'Expired' });
      http
        .expectOne('/api/auth/refresh')
        .flush({ ...response, user, sessionId, accessToken: 'replacement-token' });
      expect(failures).toBe(1);
      http.expectNone('/api/groups');
      http.expectNone('/api/auth/logout');
      expect(auth.getSessionId()).toBe(sessionId);
      fixture.detectChanges();
      http.expectOne('/api/me').flush({ ...profile, ...user });
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('input[autocomplete=name]')).toBeNull();
      expect(fixture.nativeElement.textContent).toContain(user.email);
    },
  );

  it('shares one renewal for simultaneous401s and preserves a same-device profile draft', () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const client = TestBed.inject(HttpClient);
    const auth = TestBed.inject(AuthService);
    auth.login({ email: alice.email, password: 'password123' }).subscribe();
    http.expectOne('/api/auth/login').flush(response);
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    http.expectOne('/api/me').flush(profile);
    fixture.detectChanges();
    fixture.nativeElement.querySelector('.pcard__edit').click();
    fixture.detectChanges();
    const name = fixture.nativeElement.querySelector(
      'input[autocomplete=name]',
    ) as HTMLInputElement;
    name.value = 'Unsubmitted draft';
    name.dispatchEvent(new Event('input'));
    const generation = TestBed.inject(SessionService).version;
    let completed = 0;
    client.get('/api/groups').subscribe({ next: () => completed++, error: () => {} });
    client.get('/api/dashboard').subscribe({ next: () => completed++, error: () => {} });
    http.expectOne('/api/groups').flush({}, { status: 401, statusText: 'Expired' });
    http.expectOne('/api/dashboard').flush({}, { status: 401, statusText: 'Expired' });
    const renewal = http.expectOne('/api/auth/refresh');
    renewal.flush({ ...response, accessToken: 'renewed-alice' });
    for (const path of ['/api/groups', '/api/dashboard']) {
      const retry = http.expectOne(path);
      expect(retry.request.headers.get('Authorization')).toBe('Bearer renewed-alice');
      retry.flush([]);
    }
    expect(completed).toBe(2);
    expect(TestBed.inject(SessionService).version).toBe(generation);
    fixture.detectChanges();
    expect(
      (fixture.nativeElement.querySelector('input[autocomplete=name]') as HTMLInputElement).value,
    ).toBe('Unsubmitted draft');
  });

  it('holds the native cookie lock until the complete refresh response before starting a registration', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    const http = TestBed.inject(HttpTestingController);
    const auth = TestBed.inject(AuthService);
    auth.login({ email: alice.email, password: 'password123' }).subscribe();
    http.expectOne('/api/auth/login').flush(response);
    const original = Object.getOwnPropertyDescriptor(navigator, 'locks');
    let tail: Promise<unknown> = Promise.resolve();
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: {
        request: (_name: string, operation: () => Promise<unknown>) => {
          const next = tail.then(operation);
          tail = next.then(
            () => undefined,
            () => undefined,
          );
          return next;
        },
      },
    });
    try {
      auth.restore().subscribe({ error: () => {} });
      let renewal!: TestRequest;
      await vi.waitFor(() => {
        renewal = http.expectOne('/api/auth/refresh');
      });
      auth
        .register({ email: 'bob@example.test', password: 'password123', displayName: 'Bob' })
        .subscribe();
      http.expectNone('/api/auth/register');
      renewal.flush({ ...response, accessToken: 'rotated-alice' });
      let registration!: TestRequest;
      await vi.waitFor(() => {
        registration = http.expectOne('/api/auth/register');
      });
      registration.flush({
        ...response,
        sessionId: 'bob-device',
        accessToken: 'bob-token',
        user: { id: 'bob', displayName: 'Bob', email: 'bob@example.test' },
      });
      await vi.waitFor(() => expect(auth.user()?.id).toBe('bob'));
    } finally {
      if (original) Object.defineProperty(navigator, 'locks', original);
      else Reflect.deleteProperty(navigator, 'locks');
    }
  });

  it('a delayed login cannot replace a newer account and its abandoned device is revoked', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    const http = TestBed.inject(HttpTestingController);
    const auth = TestBed.inject(AuthService);
    auth.login({ email: alice.email, password: 'password123' }).subscribe({ error: () => {} });
    const oldLogin = http.expectOne('/api/auth/login');
    const bob = { id: 'bob', email: 'bob@example.test', displayName: 'Bob' };
    auth.login({ email: bob.email, password: 'password123' }).subscribe();
    http
      .expectOne('/api/auth/login')
      .flush({ ...response, user: bob, sessionId: 'bob-device', accessToken: testJwt('bob') });
    oldLogin.flush(response);
    expect(auth.user()?.id).toBe('bob');
    const abandoned = http.expectOne('/api/auth/logout');
    expect(abandoned.request.headers.get('Authorization')).toBe('Bearer ' + response.accessToken);
    abandoned.flush(null);
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    http.expectOne('/api/me').flush({ ...profile, ...bob });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('bob@example.test');
    expect(fixture.nativeElement.textContent).not.toContain('alice@example.test');
  });

  it('shows an accurate sign-out warning when server revocation cannot be confirmed', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    const http = TestBed.inject(HttpTestingController);
    const auth = TestBed.inject(AuthService);
    auth.login({ email: alice.email, password: 'password123' }).subscribe();
    http.expectOne('/api/auth/login').flush(response);
    auth.logout();
    http.expectOne('/api/auth/logout').error(new ProgressEvent('error'));
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
    expect(auth.user()).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Server sign-out could not be confirmed');
    expect(fixture.nativeElement.textContent).toContain('copied credentials may still work');
  });

  it('signs out immediately with the captured bearer and ignores a late failure after a replacement login', () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'login', component: LoginComponent }]),
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const auth = TestBed.inject(AuthService);
    auth.login({ email: alice.email, password: 'password123' }).subscribe();
    http.expectOne('/api/auth/login').flush(response);
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    http.expectOne('/api/me').flush(profile);
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.profile__signout') as HTMLButtonElement).click();
    expect(auth.user()).toBeNull();
    expect(auth.getToken()).toBeNull();
    const signout = http.expectOne('/api/auth/logout');
    expect(signout.request.headers.get('Authorization')).toBe('Bearer ' + response.accessToken);
    expect(signout.request.withCredentials).toBe(false);
    const bob = { id: 'bob', email: 'bob@example.test', displayName: 'Bob' };
    auth.login({ email: bob.email, password: 'password123' }).subscribe();
    http
      .expectOne('/api/auth/login')
      .flush({ ...response, user: bob, sessionId: 'bob-device', accessToken: testJwt('bob') });
    signout.flush({}, { status: 503, statusText: 'Unavailable' });
    expect(auth.user()?.id).toBe('bob');
    expect(auth.getToken()).toBe(testJwt('bob'));
    fixture.destroy();
  });

  it('renews through HTTP before rendering a restored profile and discards legacy browser credentials', async () => {
    localStorage.setItem('spliteasy.accessToken', testJwt('legacy'));
    localStorage.setItem(
      'spliteasy.user',
      JSON.stringify({ id: 'legacy', email: 'legacy@example.test', displayName: 'Legacy' }),
    );
    TestBed.configureTestingModule({
      providers: [...appConfig.providers, provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    const initialized = TestBed.inject(ApplicationInitStatus);
    const renewal = http.expectOne('/api/auth/refresh');
    expect(renewal.request.withCredentials).toBe(true);
    expect(renewal.request.headers.get('X-SplitEasy-Session')).toBe('1');
    expect(renewal.request.headers.has('Authorization')).toBe(false);
    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(false);
    renewal.flush(response);
    await initialized.donePromise;
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    http.expectOne('/api/me').flush(profile);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('alice@example.test');
    expect(fixture.nativeElement.textContent).not.toContain('legacy@example.test');
    expect(localStorage.getItem('spliteasy.accessToken')).toBeNull();
    expect(localStorage.getItem('spliteasy.user')).toBeNull();
  });
});
