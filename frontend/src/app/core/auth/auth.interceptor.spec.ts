import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { vi } from 'vitest';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpTesting: HttpTestingController;
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
    http = TestBed.inject(HttpClient);
    httpTesting = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => httpTesting.verify());

  it('attaches the bearer token to API calls', () => {
    localStorage.setItem('spliteasy.accessToken', 'jwt-token');
    http.get('/api/groups').subscribe();
    const req = httpTesting.expectOne('/api/groups');
    expect(req.request.headers.get('Authorization')).toBe('Bearer jwt-token');
    req.flush([]);
  });

  it('does not attach a token to auth endpoints', () => {
    localStorage.setItem('spliteasy.accessToken', 'jwt-token');
    http.post('/api/auth/login', {}).subscribe();
    const req = httpTesting.expectOne('/api/auth/login');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('logs out and redirects to /login on 401', () => {
    localStorage.setItem('spliteasy.accessToken', 'jwt-token');
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const logout = vi.spyOn(auth, 'logout');

    http.get('/api/groups').subscribe({ error: () => {} });
    httpTesting.expectOne('/api/groups').flush(
      { message: 'nope' },
      { status: 401, statusText: 'Unauthorized' },
    );

    expect(logout).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });
});
