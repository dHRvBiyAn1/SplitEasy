import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { LoginComponent } from '../auth/login.component';
import { authInterceptor } from '../core/auth/auth.interceptor';
import { AuthService } from '../core/auth/auth.service';
import { CloseAccountComponent } from './close-account.component';

const user = { id: 'alice', email: 'alice@example.test', displayName: 'Alice' };

describe('rendered account closure', () => {
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
    TestBed.inject(HttpTestingController).expectOne('/api/auth/login').flush({
      accessToken: 'alice-token',
      tokenType: 'Bearer',
      expiresIn: 3600,
      sessionId: 'device',
      user,
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  function setup() {
    const fixture = TestBed.createComponent(CloseAccountComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    const password = el.querySelector('input[type=password]') as HTMLInputElement;
    const confirm = el.querySelector('input[type=checkbox]') as HTMLInputElement;
    const submit = () => {
      (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
      fixture.detectChanges();
    };
    const fill = () => {
      password.value = 'password123';
      password.dispatchEvent(new Event('input'));
      confirm.click();
      fixture.detectChanges();
    };
    return { fixture, el, submit, fill };
  }

  it('sends nothing until the password and explicit confirmation are given', () => {
    const { submit } = setup();
    submit();
    TestBed.inject(HttpTestingController).expectNone('/api/me/closure');
  });

  it('shows the blocking balances from a 409 and keeps the session', () => {
    const http = TestBed.inject(HttpTestingController);
    const { fixture, el, submit, fill } = setup();
    fill();
    submit();
    const req = http.expectOne('/api/me/closure');
    expect(req.request.body).toEqual({ currentPassword: 'password123' });
    req.flush(
      { message: 'Settle your balances before closing your account: Trip to Rome.' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Trip to Rome');
    expect(TestBed.inject(AuthService).user()).not.toBeNull();
  });

  it('clears the session and lands on login with a neutral notice after success', async () => {
    const http = TestBed.inject(HttpTestingController);
    const { fixture, submit, fill } = setup();
    fill();
    submit();
    http.expectOne('/api/me/closure').flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();
    expect(TestBed.inject(AuthService).user()).toBeNull();
    expect(TestBed.inject(Router).url).toBe('/login?reason=account-closed');
  });
});
