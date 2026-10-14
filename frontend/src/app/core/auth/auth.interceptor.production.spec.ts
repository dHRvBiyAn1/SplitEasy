import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { vi } from 'vitest';
import { API_BASE_URL } from '../api/api.service';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('production API authentication', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        { provide: API_BASE_URL, useValue: 'https://api.split-easy.io/api' },
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    localStorage.setItem('spliteasy.accessToken', 'production-test-jwt');
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('attaches JWT only to the API host and preserves auth/foreign-request sessions', () => {
    const http = TestBed.inject(HttpClient);
    const requests = TestBed.inject(HttpTestingController);
    const logout = vi.spyOn(TestBed.inject(AuthService), 'logout');
    http.get('https://api.split-easy.io/api/groups').subscribe();
    const privateRequest = requests.expectOne('https://api.split-easy.io/api/groups');
    expect(privateRequest.request.headers.get('Authorization')).toBe('Bearer production-test-jwt');
    privateRequest.flush([]);
    for (const url of [
      'https://api.split-easy.io/api/auth/login',
      'https://api.split-easy.io/api/auth/register',
      'https://untrusted.example/api/groups',
    ]) {
      http.post(url, {}).subscribe({ error: () => {} });
      const request = requests.expectOne(url);
      expect(request.request.headers.has('Authorization')).toBe(false);
      request.flush({}, { status: 401, statusText: 'Unauthorized' });
    }
    expect(logout).not.toHaveBeenCalled();
  });
});
