import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { vi } from 'vitest';
import { ProfileComponent } from '../../profile/profile.component';
import { SessionService } from './session.service';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';
import { storeTestSession, testAuthResponse, testJwt } from './auth-test-fixture';

const alice = { id: 'alice', displayName: 'Alice', email: 'alice@example.com' };
const bob = { id: 'bob', displayName: 'Bob', email: 'bob@example.com' };
const profile = (user = alice) => ({
  ...user,
  emailVerified: false,
  phone: '',
  preferredCurrency: 'USD',
  avatarColor: '#12A57E',
  createdAt: '2020-01-01T00:00:00Z',
});
function remoteLogin(): string {
  const marker = JSON.stringify({ id: crypto.randomUUID(), signedOut: false });
  localStorage.setItem('spliteasy.auth-change', marker);
  return marker;
}
function deliverMarker(): void {
  window.dispatchEvent(
    new StorageEvent('storage', {
      key: 'spliteasy.auth-change',
      storageArea: localStorage,
      newValue: localStorage.getItem('spliteasy.auth-change'),
    }),
  );
}

describe('cross-tab authentication ownership', () => {
  let http: HttpTestingController;
  let auth: AuthService;
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    storeTestSession(alice);
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });
  afterEach(() => http.verify());

  it('keeps an old draft on its own bearer until marker delivery, then cancels it and restores the replacement account', () => {
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    http.expectOne('/api/me').flush(profile());
    fixture.detectChanges();
    const change = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((button) => button.textContent?.trim() === 'Change password')!;
    change.click();
    fixture.detectChanges();
    for (const [selector, value] of [
      ['#security-current-password', 'old-password'],
      ['#security-new-password', 'new-password'],
    ]) {
      const field = fixture.nativeElement.querySelector(selector);
      field.value = value;
      field.dispatchEvent(new Event('input'));
    }
    remoteLogin();
    fixture.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    const pending = http.expectOne('/api/me/password');
    expect(pending.request.headers.get('Authorization')).toBe('Bearer ' + testJwt('alice'));
    deliverMarker();
    expect(pending.cancelled).toBe(true);
    expect(auth.user()).toBeNull();
    http.expectOne('/api/auth/refresh').flush(testAuthResponse(bob));
    fixture.detectChanges();
    http.expectOne('/api/me').flush(profile(bob));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('bob@example.com');
    expect(fixture.nativeElement.querySelector('input[type=password]')).toBeNull();
    expect(auth.getToken()).toBe(testJwt('bob'));
  });

  it('does not revoke a newer unseen tab login when an old bearer returns401', () => {
    TestBed.inject(HttpClient)
      .get('/api/groups')
      .subscribe({ error: () => {} });
    const pending = http.expectOne('/api/groups');
    const marker = remoteLogin();
    pending.flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/auth/refresh').flush(testAuthResponse(bob));
    http.expectNone('/api/groups');
    http.expectNone('/api/auth/logout');
    expect(localStorage.getItem('spliteasy.auth-change')).toBe(marker);
    expect(auth.user()?.id).toBe('bob');
    deliverMarker();
    http.expectNone('/api/auth/refresh');
    expect(auth.getToken()).toBe(testJwt('bob'));
  });

  it('ignores partial or malformed legacy storage instead of adopting another bearer', () => {
    for (const raw of [testJwt('bob'), 'malformed']) {
      localStorage.setItem('spliteasy.accessToken', raw);
      localStorage.setItem('spliteasy.user', '{bad');
      window.dispatchEvent(
        new StorageEvent('storage', { key: 'spliteasy.user', storageArea: localStorage }),
      );
      expect(auth.user()?.id).toBe('alice');
      expect(auth.getToken()).toBe(testJwt('alice'));
    }
    auth.logout();
    const signout = http.expectOne('/api/auth/logout');
    expect(signout.request.headers.get('Authorization')).toBe('Bearer ' + testJwt('alice'));
    signout.flush(null);
  });

  it('preserves the newer shared marker when an old tab signs out before its storage event', () => {
    const marker = remoteLogin();
    auth.logout();
    expect(localStorage.getItem('spliteasy.auth-change')).toBe(marker);
    const signout = http.expectOne('/api/auth/logout');
    expect(signout.request.headers.get('Authorization')).toBe('Bearer ' + testJwt('alice'));
    signout.flush(null);
    deliverMarker();
    http.expectOne('/api/auth/refresh').flush(testAuthResponse(bob));
    expect(auth.user()?.id).toBe('bob');
    expect(localStorage.getItem('spliteasy.auth-change')).toBe(marker);
  });

  it('ignores an old profile callback and keeps the generation during a same-device name renewal', () => {
    const oldVersion = TestBed.inject(SessionService).version;
    const marker = remoteLogin();
    auth.applyProfileName('alice', oldVersion, 'Late Alice');
    expect(localStorage.getItem('spliteasy.auth-change')).toBe(marker);
    deliverMarker();
    http.expectOne('/api/auth/refresh').flush(testAuthResponse(bob));
    auth.applyProfileName('alice', oldVersion, 'Wrong replacement');
    expect(auth.user()?.displayName).toBe('Bob');
    const version = TestBed.inject(SessionService).version;
    auth.restore().subscribe();
    http
      .expectOne('/api/auth/refresh')
      .flush(testAuthResponse({ ...bob, displayName: 'Fresh Bob' }));
    expect(auth.user()?.displayName).toBe('Fresh Bob');
    expect(TestBed.inject(SessionService).version).toBe(version);
  });
});
