import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';
import { AuthResponse } from './auth.models';

const authResponse: AuthResponse = {
  accessToken: 'jwt-token',
  tokenType: 'Bearer',
  expiresIn: 3600,
  user: { id: 'u1', email: 'alice@example.com', displayName: 'Alice' },
};

describe('AuthService', () => {
  let service: AuthService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('starts unauthenticated', () => {
    expect(service.isAuthenticated()).toBe(false);
    expect(service.getToken()).toBeNull();
  });

  it('register stores token + user and flips isAuthenticated', () => {
    service.register({ email: 'alice@example.com', password: 'password123', displayName: 'Alice' }).subscribe();
    const req = httpTesting.expectOne('/api/auth/register');
    expect(req.request.method).toBe('POST');
    req.flush(authResponse);

    expect(service.getToken()).toBe('jwt-token');
    expect(service.isAuthenticated()).toBe(true);
    expect(service.user()?.email).toBe('alice@example.com');
  });

  it('login persists the session across a new service instance', () => {
    service.login({ email: 'alice@example.com', password: 'password123' }).subscribe();
    httpTesting.expectOne('/api/auth/login').flush(authResponse);

    // A fresh instance should rehydrate from localStorage.
    const rehydrated = TestBed.inject(AuthService);
    expect(rehydrated.getToken()).toBe('jwt-token');
  });

  it('logout clears token and user', () => {
    service.login({ email: 'alice@example.com', password: 'password123' }).subscribe();
    httpTesting.expectOne('/api/auth/login').flush(authResponse);

    service.logout();
    expect(service.isAuthenticated()).toBe(false);
    expect(service.getToken()).toBeNull();
    expect(localStorage.getItem('spliteasy.accessToken')).toBeNull();
  });
});
