import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { authInterceptor } from '../core/auth/auth.interceptor';
import { LoginComponent } from '../auth/login.component';
import { ProfileComponent } from './profile.component';

const user = { id: 'alice', email: 'alice@example.test', displayName: 'Alice' };
const response = {
  accessToken: 'alice-token',
  tokenType: 'Bearer',
  expiresIn: 3600,
  sessionId: 'current-device',
  user,
};
const profile = {
  ...user,
  emailVerified: false,
  phone: '',
  preferredCurrency: 'USD',
  avatarColor: '#12A57E',
  createdAt: '2020-01-01T00:00:00Z',
};
const current = {
  id: 'current-device',
  deviceLabel: 'Chrome on Windows',
  createdAt: '2026-10-20T00:00:00Z',
  refreshedAt: '2026-10-24T00:00:00Z',
  expiresAt: '2026-11-19T00:00:00Z',
  current: true,
};
const other = { ...current, id: 'other-device', deviceLabel: 'Firefox on Linux', current: false };

describe('rendered profile device sessions', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([{ path: 'login', component: LoginComponent }]),
      ],
    });
    TestBed.inject(AuthService).login({ email: user.email, password: 'password123' }).subscribe();
    TestBed.inject(HttpTestingController).expectOne('/api/auth/login').flush(response);
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('allows manual retry after list and revoke failures, then signs out this device with its captured bearer', () => {
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    http.expectOne('/api/me').flush(profile);
    fixture.detectChanges();
    const button = (label: string) =>
      Array.from(
        fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
      ).find((candidate) => candidate.textContent?.trim() === label)!;
    button('Manage sessions').click();
    http.expectOne('/api/me/sessions').flush({}, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Unable to load sessions');
    button('Refresh sessions').click();
    http.expectOne('/api/me/sessions').flush([current, other]);
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(
        'button[aria-label="Sign out Firefox on Linux"]',
      ) as HTMLButtonElement
    ).click();
    http.expectOne('/api/me/sessions/other-device').error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('sign-out could not be confirmed');
    (
      fixture.nativeElement.querySelector(
        'button[aria-label="Sign out Firefox on Linux"]',
      ) as HTMLButtonElement
    ).click();
    http.expectOne('/api/me/sessions/other-device').flush(null);
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(
        'button[aria-label="Sign out this device"]',
      ) as HTMLButtonElement
    ).click();
    const command = http.expectOne('/api/auth/logout');
    expect(command.request.headers.get('Authorization')).toBe('Bearer alice-token');
    expect(TestBed.inject(AuthService).user()).toBeNull();
    command.flush(null);
  });

  it('signs out locally at once and accurately reports an unconfirmed sign-out of all devices', () => {
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    http.expectOne('/api/me').flush(profile);
    fixture.detectChanges();
    Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find((button) => button.textContent?.trim() === 'Manage sessions')!
      .click();
    http.expectOne('/api/me/sessions').flush([current, other]);
    fixture.detectChanges();
    const all = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((button) => button.textContent?.trim() === 'Sign out all devices');
    expect(all).toBeDefined();
    all!.click();
    expect(TestBed.inject(AuthService).user()).toBeNull();
    const command = http.expectOne('/api/me/sessions/logout-all');
    expect(command.request.method).toBe('POST');
    expect(command.request.headers.get('Authorization')).toBe('Bearer alice-token');
    command.flush({}, { status: 503, statusText: 'Unavailable' });
    const login = TestBed.createComponent(LoginComponent);
    login.detectChanges();
    expect(login.nativeElement.textContent).toContain('Server sign-out could not be confirmed');
    expect(login.nativeElement.textContent).toContain(
      'other devices and copied credentials may still work',
    );
  });

  it('loads owned devices on request and signs out another device while retaining this one', () => {
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ProfileComponent);
    fixture.detectChanges();
    http.expectOne('/api/me').flush(profile);
    fixture.detectChanges();
    const manage = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((button) => button.textContent?.trim() === 'Manage sessions');
    expect(manage).toBeDefined();
    manage!.click();
    fixture.detectChanges();
    const list = http.expectOne('/api/me/sessions');
    expect(list.request.headers.get('Authorization')).toBe('Bearer alice-token');
    expect(manage!.disabled).toBe(true);
    list.flush([current, other]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('This device');
    expect(fixture.nativeElement.textContent).toContain('Firefox on Linux');
    const revoke = fixture.nativeElement.querySelector(
      'button[aria-label="Sign out Firefox on Linux"]',
    ) as HTMLButtonElement;
    revoke.click();
    const command = http.expectOne('/api/me/sessions/other-device');
    expect(command.request.method).toBe('DELETE');
    expect(command.request.headers.get('Authorization')).toBe('Bearer alice-token');
    command.flush(null);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Firefox on Linux');
    expect(fixture.nativeElement.textContent).toContain('Chrome on Windows');
    expect(TestBed.inject(AuthService).user()?.id).toBe('alice');
  });
});
