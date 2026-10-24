import { vi } from 'vitest';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from '../core/auth/auth.service';
import { authInterceptor } from '../core/auth/auth.interceptor';
import { ProfileComponent } from './profile.component';

describe('profile account security', () => {
  let http: HttpTestingController;
  let auth: AuthService;
  const user = { id: 'alice', displayName: 'Alice', email: 'alice@example.com' };
  const profile = {
    ...user,
    emailVerified: false,
    phone: '',
    preferredCurrency: 'USD',
    avatarColor: '#12A57E',
    createdAt: '2020-01-01T00:00:00Z',
  };
  function input(root: HTMLElement, selector: string, value: string): void {
    const field = root.querySelector<HTMLInputElement>(selector)!;
    field.value = value;
    field.dispatchEvent(new Event('input'));
  }
  function button(root: HTMLElement, label: string): HTMLButtonElement {
    return Array.from(root.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === label,
    )!;
  }
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
    auth.login({ email: user.email, password: 'password' }).subscribe();
    http.expectOne('/api/auth/login').flush({ accessToken: 'alice-token', user });
  });
  afterEach(() => http.verify());
  it('focuses linked password errors, lets each link focus its field and focuses the remaining single error', () => {
    const f = TestBed.createComponent(ProfileComponent);
    f.detectChanges();
    http.expectOne('/api/me').flush(profile);
    f.detectChanges();
    button(f.nativeElement, 'Change password').click();
    f.detectChanges();
    const submit = () =>
      f.nativeElement
        .querySelector('#password-change-form')
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    button(f.nativeElement, 'Save password').focus();
    submit();
    f.detectChanges();
    const summary = f.nativeElement.querySelector('#password-errors');
    expect(summary).not.toBeNull();
    expect(document.activeElement).toBe(summary);
    const links = summary.querySelectorAll('a');
    expect(links.length).toBe(2);
    links[0].click();
    expect(document.activeElement).toBe(
      f.nativeElement.querySelector('#security-current-password'),
    );
    links[1].click();
    expect(document.activeElement).toBe(f.nativeElement.querySelector('#security-new-password'));
    input(f.nativeElement, '#security-current-password', 'password');
    submit();
    f.detectChanges();
    expect(document.activeElement).toBe(f.nativeElement.querySelector('#security-new-password'));
    expect(f.nativeElement.querySelector('#password-errors')).toBeNull();
    http.expectNone('/api/me/password');
  });
  it('focuses linked email-change errors and the remaining invalid field without submitting', () => {
    const f = TestBed.createComponent(ProfileComponent);
    f.detectChanges();
    http.expectOne('/api/me').flush(profile);
    f.detectChanges();
    button(f.nativeElement, 'Change email').click();
    f.detectChanges();
    const submit = () =>
      f.nativeElement
        .querySelector('#email-change-form')
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    button(f.nativeElement, 'Request email change').focus();
    submit();
    f.detectChanges();
    const summary = f.nativeElement.querySelector('#email-errors');
    expect(summary).not.toBeNull();
    expect(document.activeElement).toBe(summary);
    const links = summary.querySelectorAll('a');
    expect(links.length).toBe(2);
    links[0].click();
    expect(document.activeElement).toBe(f.nativeElement.querySelector('#email-current-password'));
    links[1].click();
    expect(document.activeElement).toBe(f.nativeElement.querySelector('#security-new-email'));
    input(f.nativeElement, '#security-new-email', 'new@example.com');
    submit();
    f.detectChanges();
    expect(document.activeElement).toBe(f.nativeElement.querySelector('#email-current-password'));
    expect(f.nativeElement.querySelector('#email-errors')).toBeNull();
    http.expectNone('/api/me/email-change-requests');
  });

  it('shows server-owned verification state and explicitly requests verification without pretending delivery or success', () => {
    const f = TestBed.createComponent(ProfileComponent);
    f.detectChanges();
    http.expectOne('/api/me').flush(profile);
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Email not verified');
    button(f.nativeElement, 'Send verification link').click();
    const request = http.expectOne('/api/me/email-verification-requests');
    expect(request.request.body).toEqual({});
    expect(request.request.headers.get('Authorization')).toBe('Bearer alice-token');
    request.flush(null, { status: 202, statusText: 'Accepted' });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('A verification link may arrive');
    expect(f.nativeElement.textContent).toContain('Email not verified');
    button(f.nativeElement, 'Refresh profile').click();
    http.expectOne('/api/me').flush({ ...profile, emailVerified: true });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Email verified');
    expect(button(f.nativeElement, 'Send verification link')).toBeUndefined();
  });
  it('changes a password with the current password, clears secrets and logs only the originating session out', () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const f = TestBed.createComponent(ProfileComponent);
    f.detectChanges();
    http.expectOne('/api/me').flush(profile);
    f.detectChanges();
    button(f.nativeElement, 'Change password').click();
    f.detectChanges();
    input(f.nativeElement, '#security-current-password', 'password');
    input(f.nativeElement, '#security-new-password', 'new-password');
    f.nativeElement
      .querySelector('#password-change-form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    const request = http.expectOne('/api/me/password');
    expect(request.request.body).toEqual({ currentPassword: 'password', password: 'new-password' });
    request.flush(null, { status: 204, statusText: 'No Content' });
    f.detectChanges();
    expect(auth.user()).toBeNull();
    expect(localStorage.getItem('spliteasy.accessToken')).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { reason: 'password-changed' },
    });
    expect(f.nativeElement.querySelector('input[type=password]')).toBeNull();
  });

  it('requests a password-confirmed email change, retains the old login email and clears the accepted password', () => {
    const f = TestBed.createComponent(ProfileComponent);
    f.detectChanges();
    http.expectOne('/api/me').flush(profile);
    f.detectChanges();
    button(f.nativeElement, 'Change email').click();
    f.detectChanges();
    input(f.nativeElement, '#email-current-password', 'password');
    input(f.nativeElement, '#security-new-email', 'new@example.com');
    const submit = () =>
      f.nativeElement
        .querySelector('#email-change-form')
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    submit();
    const first = http.expectOne('/api/me/email-change-requests');
    expect(first.request.body).toEqual({ currentPassword: 'password', email: 'new@example.com' });
    first.flush({ message: 'Email already in use' }, { status: 409, statusText: 'Conflict' });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('already in use');
    expect(f.nativeElement.querySelector('#email-current-password').value).toBe('password');
    submit();
    http
      .expectOne('/api/me/email-change-requests')
      .flush(null, { status: 202, statusText: 'Accepted' });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('new@example.com');
    expect(f.nativeElement.textContent).toContain(
      'current email remains your login until confirmation',
    );
    expect(f.nativeElement.querySelector('input[type=password]')).toBeNull();
    expect(auth.user()?.email).toBe('alice@example.com');
    expect(auth.getToken()).toBe('alice-token');
  });

  it('validates sensitive forms and shows rate-limit and mail errors while retaining drafts for explicit retries', () => {
    const f = TestBed.createComponent(ProfileComponent);
    f.detectChanges();
    http.expectOne('/api/me').flush(profile);
    f.detectChanges();
    button(f.nativeElement, 'Change password').click();
    f.detectChanges();
    input(f.nativeElement, '#security-current-password', 'é'.repeat(37));
    input(f.nativeElement, '#security-new-password', 'new-password');
    f.nativeElement
      .querySelector('#password-change-form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    f.detectChanges();
    expect(
      f.nativeElement.querySelector('#security-current-password').getAttribute('aria-invalid'),
    ).toBe('true');
    http.expectNone('/api/me/password');
    input(f.nativeElement, '#security-current-password', 'password');
    input(f.nativeElement, '#security-new-password', 'é'.repeat(37));
    f.nativeElement
      .querySelector('#password-change-form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    f.detectChanges();
    expect(
      f.nativeElement.querySelector('#security-new-password').getAttribute('aria-invalid'),
    ).toBe('true');
    http.expectNone('/api/me/password');
    button(f.nativeElement, 'Cancel').click();
    f.detectChanges();
    button(f.nativeElement, 'Change email').click();
    f.detectChanges();
    input(f.nativeElement, '#email-current-password', 'password');
    input(f.nativeElement, '#security-new-email', 'new@example.com');
    const submit = () =>
      f.nativeElement
        .querySelector('#email-change-form')
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    submit();
    http
      .expectOne('/api/me/email-change-requests')
      .flush({}, { status: 429, statusText: 'Limited', headers: { 'Retry-After': '60' } });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Too many attempts');
    submit();
    http
      .expectOne('/api/me/email-change-requests')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Email is temporarily unavailable');
    expect(f.nativeElement.querySelector('#security-new-email').value).toBe('new@example.com');
    expect(auth.getToken()).toBe('alice-token');
  });
});
