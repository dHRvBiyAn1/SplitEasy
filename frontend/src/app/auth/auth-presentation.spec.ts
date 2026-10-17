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
});
