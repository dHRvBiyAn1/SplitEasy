import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';
import { AuthResponse } from './auth.models';

const authResponse: AuthResponse = {
  accessToken: 'jwt-token',
  sessionId: 'u1-device',
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

  afterEach(() => {
    httpTesting.match('/api/auth/logout').forEach((request) => request.flush(null));
    httpTesting.verify();
  });

  it('keeps a successful login credential only in memory', () => {
    service.login({ email: 'alice@example.com', password: 'password123' }).subscribe();
    httpTesting.expectOne('/api/auth/login').flush(authResponse);
    expect(service.user()?.id).toBe('u1');
    expect(service.getToken()).toBe('jwt-token');
    expect(localStorage.getItem('spliteasy.accessToken')).toBeNull();
    expect(localStorage.getItem('spliteasy.user')).toBeNull();
  });

  it('starts unauthenticated', () => {
    expect(service.isAuthenticated()).toBe(false);
    expect(service.getToken()).toBeNull();
  });

  it('register authenticates the returned user in memory', () => {
    service
      .register({ email: 'alice@example.com', password: 'password123', displayName: 'Alice' })
      .subscribe();
    const req = httpTesting.expectOne('/api/auth/register');
    expect(req.request.method).toBe('POST');
    req.flush(authResponse);

    expect(service.getToken()).toBe('jwt-token');
    expect(service.isAuthenticated()).toBe(true);
    expect(service.user()?.email).toBe('alice@example.com');
  });

  it('restores through the cookie after a real service reload instead of reading a stored token', () => {
    service.login({ email: 'alice@example.com', password: 'password123' }).subscribe();
    httpTesting.expectOne('/api/auth/login').flush(authResponse);
    httpTesting.verify();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpTesting = TestBed.inject(HttpTestingController);
    expect(service.getToken()).toBeNull();
    service.restore().subscribe();
    const renewal = httpTesting.expectOne('/api/auth/refresh');
    expect(renewal.request.withCredentials).toBe(true);
    renewal.flush({ ...authResponse, accessToken: 'renewed-jwt' });
    expect(service.getToken()).toBe('renewed-jwt');
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
