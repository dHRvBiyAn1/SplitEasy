import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthResponse } from './auth/auth.models';
import { AuthService } from './auth/auth.service';
import { AVATAR_COLORS, ProfileService } from './profile.service';
import { ThemeService } from './theme.service';
import { ProfileComponent } from '../profile/profile.component';

const alice: AuthResponse = {
  accessToken: 'alice-token',
  tokenType: 'Bearer',
  expiresIn: 3600,
  user: { id: 'alice-id', email: 'alice@example.com', displayName: 'Alice' },
};
const bob: AuthResponse = {
  ...alice,
  user: { id: 'bob-id', email: 'bob@example.com', displayName: 'Bob' },
};
const alicePrefs = {
  phone: '+91 0000000000',
  currency: 'INR' as const,
  avatarColor: AVATAR_COLORS[2],
  joinedAt: '2025-01-01T00:00:00.000Z',
};

describe('account-owned profile preferences', () => {
  let auth: AuthService;
  let profile: ProfileService;
  let requests: HttpTestingController;

  function boot(): void {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    auth = TestBed.inject(AuthService);
    profile = TestBed.inject(ProfileService);
    requests = TestBed.inject(HttpTestingController);
  }

  function login(response: AuthResponse, register = false): void {
    if (register) auth.register({ ...response.user, password: 'test-password' }).subscribe();
    else auth.login({ email: response.user.email, password: 'test-password' }).subscribe();
    requests.expectOne(`/api/auth/${register ? 'register' : 'login'}`).flush(response);
  }

  beforeEach(() => {
    localStorage.clear();
    boot();
  });
  afterEach(() => requests.verify());

  it('isolates A to B to A synchronously and preserves each account across a real injector reload', () => {
    login(alice);
    profile.update(alicePrefs);
    auth.logout();
    expect(profile.prefs().phone).toBe('');
    login(bob);
    expect(profile.prefs()).toMatchObject({
      phone: '',
      currency: 'USD',
      avatarColor: AVATAR_COLORS[0],
    });
    expect(profile.prefs().joinedAt).not.toBe(alicePrefs.joinedAt);
    profile.update({ phone: '+1 0000000000', currency: 'EUR', avatarColor: AVATAR_COLORS[1] });
    const bobPrefs = profile.prefs();
    login(alice);
    expect(profile.prefs()).toEqual(alicePrefs);
    requests.verify();
    TestBed.resetTestingModule();
    boot();
    expect(auth.user()?.id).toBe(alice.user.id);
    expect(profile.prefs()).toEqual(alicePrefs);
    login(bob);
    expect(profile.prefs()).toEqual(bobPrefs);
  });

  it('uses the new identity for an immediate partial write before any render or preference read', () => {
    login(alice);
    profile.update(alicePrefs);
    login(bob);
    profile.update({ currency: 'GBP' });
    expect(profile.prefs()).toMatchObject({ phone: '', currency: 'GBP' });
    login(alice);
    expect(profile.prefs()).toEqual(alicePrefs);
  });

  it('starts a newly registered account with its own defaults', () => {
    login(alice);
    profile.update(alicePrefs);
    login(bob, true);
    expect(profile.prefs()).toMatchObject({
      phone: '',
      currency: 'USD',
      avatarColor: AVATAR_COLORS[0],
    });
    login(alice);
    expect(profile.prefs()).toEqual(alicePrefs);
  });

  it('keeps preferences when mutable name and email change', () => {
    login(alice);
    profile.update(alicePrefs);
    login({ ...alice, user: { ...alice.user, displayName: 'New name', email: 'new@example.com' } });
    expect(profile.prefs()).toEqual(alicePrefs);
  });

  it('discards ownerless legacy fields without changing device appearance', () => {
    requests.verify();
    TestBed.resetTestingModule();
    localStorage.setItem('spliteasy.profile', JSON.stringify(alicePrefs));
    localStorage.setItem('spliteasy.theme', 'dark');
    localStorage.setItem('spliteasy.palette', 'peach');
    boot();
    login(bob);
    expect(profile.prefs()).toMatchObject({
      phone: '',
      currency: 'USD',
      avatarColor: AVATAR_COLORS[0],
    });
    expect(localStorage.getItem('spliteasy.profile')).toBeNull();
    expect(TestBed.inject(ThemeService).choice()).toBe('dark');
    expect(TestBed.inject(ThemeService).palette()).toBe('peach');
    login(alice);
    expect(profile.prefs().phone).toBe('');
  });

  it('does not persist unauthenticated profile changes into the next account', () => {
    profile.update(alicePrefs);
    login(bob);
    expect(profile.prefs().phone).toBe('');
    expect(localStorage.getItem('spliteasy.profile')).toBeNull();
  });

  it('closes an old account’s profile draft and ignores its stale submit on identity change', () => {
    login(alice);
    profile.update(alicePrefs);
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    fixture.componentInstance.startEdit();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('input[autocomplete=tel]').value).toBe(
      alicePrefs.phone,
    );
    login(bob);
    fixture.componentInstance.save();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Bob');
    expect(fixture.nativeElement.textContent).not.toContain(alicePrefs.phone);
    expect(auth.user()).toEqual(bob.user);
    expect(profile.prefs().phone).toBe('');
    fixture.componentInstance.startEdit();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('input[autocomplete=tel]').value).toBe('');
  });
});
