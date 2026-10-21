import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { vi } from 'vitest';
import { ProfileComponent } from '../../profile/profile.component';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';
import { storeTestSession, testJwt } from './auth-test-fixture';

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
function storageEvent(): void {
  window.dispatchEvent(
    new StorageEvent('storage', {
      key: 'spliteasy.user',
      storageArea: localStorage,
      newValue: localStorage.getItem('spliteasy.user'),
    }),
  );
}
describe('cross-tab authentication ownership', () => {
  let http: HttpTestingController;
  let auth: AuthService;
  beforeEach(() => {
    localStorage.clear();
    storeTestSession(alice);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });
  afterEach(() => http.verify());
  it('keeps an old draft on its own credential until storage delivery, then cancels it and switches the rendered account', () => {
    const f = TestBed.createComponent(ProfileComponent);
    f.detectChanges();
    http.expectOne('/api/me').flush(profile());
    f.detectChanges();
    const change = Array.from(
      f.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((button) => button.textContent?.trim() === 'Change password')!;
    change.click();
    f.detectChanges();
    for (const [selector, value] of [
      ['#security-current-password', 'old-password'],
      ['#security-new-password', 'new-password'],
    ]) {
      const field = f.nativeElement.querySelector(selector);
      field.value = value;
      field.dispatchEvent(new Event('input'));
    }
    storeTestSession(bob);
    f.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    const pending = http.expectOne('/api/me/password');
    expect(pending.request.headers.get('Authorization')).toBe('Bearer ' + testJwt('alice'));
    storageEvent();
    expect(pending.cancelled).toBe(true);
    expect(auth.user()?.id).toBe('bob');
    f.detectChanges();
    http.expectOne('/api/me').flush(profile(bob));
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('bob@example.com');
    expect(f.nativeElement.querySelector('input[type=password]')).toBeNull();
    expect(auth.getToken()).toBe(testJwt('bob'));
  });
  it('preserves a newer tab login when an old credential returns 401 before its storage event', () => {
    TestBed.inject(HttpClient)
      .get('/api/groups')
      .subscribe({ error: () => {} });
    const pending = http.expectOne('/api/groups');
    storeTestSession(bob);
    pending.flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(localStorage.getItem('spliteasy.accessToken')).toBe(testJwt('bob'));
    expect(JSON.parse(localStorage.getItem('spliteasy.user')!).id).toBe('bob');
    storageEvent();
    expect(auth.user()?.id).toBe('bob');
    expect(auth.getToken()).toBe(testJwt('bob'));
  });
  it('rejects partial and malformed storage pairs without erasing the newer shared credentials', () => {
    localStorage.setItem('spliteasy.accessToken', testJwt('bob'));
    storageEvent();
    expect(auth.user()).toBeNull();
    expect(auth.getToken()).toBeNull();
    auth.logout();
    expect(localStorage.getItem('spliteasy.accessToken')).toBe(testJwt('bob'));
    localStorage.setItem('spliteasy.user', '{bad');
    storageEvent();
    expect(auth.isAuthenticated()).toBe(false);
    localStorage.setItem('spliteasy.user', JSON.stringify(bob));
    storageEvent();
    expect(auth.user()?.id).toBe('bob');
    localStorage.setItem('spliteasy.accessToken', 'malformed');
    storageEvent();
    expect(auth.user()).toBeNull();
  });
  it('does not let an old profile response overwrite a newer tab identity or turn a name update into a login generation', () => {
    const f = TestBed.createComponent(ProfileComponent);
    f.detectChanges();
    const old = http.expectOne('/api/me');
    storeTestSession(bob);
    old.flush({ ...profile(), displayName: 'Fresh Alice' });
    expect(JSON.parse(localStorage.getItem('spliteasy.user')!).id).toBe('bob');
    storageEvent();
    f.detectChanges();
    http.expectOne('/api/me').flush(profile(bob));
    f.detectChanges();
    localStorage.setItem('spliteasy.user', JSON.stringify({ ...bob, displayName: 'Fresh Bob' }));
    storageEvent();
    f.detectChanges();
    expect(auth.user()?.displayName).toBe('Fresh Bob');
    http.expectNone('/api/me');
  });
});
