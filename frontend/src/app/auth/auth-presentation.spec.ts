import { AuthService } from '../core/auth/auth.service';
import { storeTestSession, testJwt } from '../core/auth/auth-test-fixture';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { LoginComponent } from './login.component';
import { RegisterComponent } from './register.component';

describe('authentication presentation', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
  });
  afterEach(() => TestBed.inject(HttpTestingController).verify());
  it('shows usable validation instead of silently ignoring an empty login', () => {
    const f = TestBed.createComponent(LoginComponent);
    f.detectChanges();
    f.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Enter a valid email address');
    expect(f.nativeElement.textContent).toContain('Enter your password');
    TestBed.inject(HttpTestingController).expectNone('/api/auth/login');
  });
  it('reveals the password without submitting or changing its accessible label', () => {
    const f = TestBed.createComponent(LoginComponent);
    f.detectChanges();
    const input = f.nativeElement.querySelector('#auth-password') as HTMLInputElement;
    f.nativeElement.querySelector('[aria-label="Show password"]').click();
    f.detectChanges();
    expect(input.type).toBe('text');
    f.nativeElement.querySelector('[aria-label="Hide password"]').click();
    f.detectChanges();
    expect(input.type).toBe('password');
    TestBed.inject(HttpTestingController).expectNone('/api/auth/login');
  });
  it('shows the name and minimum-password requirements before registration', () => {
    const f = TestBed.createComponent(RegisterComponent);
    f.detectChanges();
    f.nativeElement
      .querySelector('form')
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Enter a name');
    expect(f.nativeElement.textContent).toContain('Use at least 8 characters');
    TestBed.inject(HttpTestingController).expectNone('/api/auth/register');
  });
  for (const password of [
    'a'.repeat(73),
    'é'.repeat(40),
    '😀'.repeat(19),
    'a'.repeat(72),
    'é'.repeat(36),
    '😀'.repeat(18),
  ]) {
    const bytes = new TextEncoder().encode(password).length;
    it(`${bytes > 72 ? 'rejects' : 'submits'} a ${bytes}-byte password unchanged (${password.codePointAt(0)})`, () => {
      const f = TestBed.createComponent(RegisterComponent);
      f.detectChanges();
      for (const [name, value] of Object.entries({
        displayName: 'Alice',
        email: 'alice@example.com',
        password,
      })) {
        const input = f.nativeElement.querySelector(
          `[formControlName="${name}"]`,
        ) as HTMLInputElement;
        input.value = value;
        input.dispatchEvent(new Event('input'));
      }
      f.nativeElement
        .querySelector('form')
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      f.detectChanges();
      const input = f.nativeElement.querySelector('#auth-password') as HTMLInputElement;
      expect(input.value).toBe(password);
      const http = TestBed.inject(HttpTestingController);
      if (bytes > 72) {
        expect(input.getAttribute('aria-invalid')).toBe('true');
        expect(f.nativeElement.querySelector('#password-hint').textContent).toContain(
          '72 UTF-8 bytes',
        );
        http.expectNone('/api/auth/register');
      } else {
        const request = http.expectOne('/api/auth/register');
        expect(request.request.body.password).toBe(password);
        request.flush({ message: 'test complete' }, { status: 400, statusText: 'Bad Request' });
      }
    });
  }

  for (const component of [LoginComponent, RegisterComponent]) {
    it(`shows a friendly retry notice for ${component.name} without changing session state`, () => {
      storeTestSession({ id: 'alice', email: 'alice@example.com', displayName: 'Alice' });
      const f = TestBed.createComponent<LoginComponent | RegisterComponent>(component);
      f.detectChanges();
      for (const [selector, value] of [
        ['[formControlName="email"]', 'alice@example.com'],
        ['#auth-password', 'password123'],
        ['[formControlName="displayName"]', 'Alice'],
      ]) {
        const input = f.nativeElement.querySelector(selector) as HTMLInputElement | null;
        if (input) {
          input.value = value;
          input.dispatchEvent(new Event('input'));
        }
      }
      f.nativeElement
        .querySelector('form')
        .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      const http = TestBed.inject(HttpTestingController);
      http
        .expectOne(component === LoginComponent ? '/api/auth/login' : '/api/auth/register')
        .flush(
          {},
          { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '300' } },
        );
      f.detectChanges();
      expect(f.nativeElement.textContent).toContain(
        'Too many attempts. Please wait before trying again.',
      );
      expect(f.nativeElement.querySelector('button[type="submit"]').disabled).toBe(false);
      expect(TestBed.inject(AuthService).getToken()).toBe(testJwt('alice'));
      expect(localStorage.getItem('spliteasy.accessToken')).toBeNull();
    });
  }
});
