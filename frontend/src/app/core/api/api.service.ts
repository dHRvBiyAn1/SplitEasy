import { HttpClient } from '@angular/common/http';
import { Injectable, InjectionToken, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** Configured API root: relative in development, the trusted API origin in production. */
export const API_BASE = environment.apiBase;
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => API_BASE,
});

/** Auth endpoints (register/login) — the interceptor leaves these untouched (no bearer, no 401 bounce). */
export const AUTH_URL_PREFIX = `${API_BASE}/auth/`;

/** Normalize before checking boundaries, so foreign hosts and path traversal never receive a JWT. */
export function apiPath(url: string, base: string = API_BASE): string | null {
  try {
    const root = new URL(base, document.baseURI);
    const request = new URL(url, document.baseURI);
    if (request.origin !== root.origin || request.username || request.password) return null;
    if (request.pathname === root.pathname) return '/';
    return request.pathname.startsWith(`${root.pathname}/`)
      ? request.pathname.slice(root.pathname.length)
      : null;
  } catch {
    return null;
  }
}

/**
 * Shared HTTP layer for the SplitEasy backend. All resource services
 * (health, groups, expenses, ...) go through this instead of using
 * HttpClient directly, so base URL and cross-cutting concerns live in
 * one place. `/api` is proxied to the backend in dev (see proxy.conf.json).
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  private ledgerOptions(path: string) {
    return path === '/dashboard' || path.startsWith('/groups')
      ? { headers: { 'X-Ledger-Version': '2' } }
      : {};
  }

  get<T>(path: string): Observable<T> {
    return this.http.get<T>(`${this.baseUrl}${path}`, this.ledgerOptions(path));
  }

  post<T>(path: string, body: unknown): Observable<T> {
    return this.http.post<T>(`${this.baseUrl}${path}`, body, this.ledgerOptions(path));
  }

  put<T>(path: string, body: unknown): Observable<T> {
    return this.http.put<T>(`${this.baseUrl}${path}`, body, this.ledgerOptions(path));
  }

  patch<T>(path: string, body: unknown): Observable<T> {
    return this.http.patch<T>(`${this.baseUrl}${path}`, body, this.ledgerOptions(path));
  }

  delete<T>(path: string): Observable<T> {
    return this.http.delete<T>(`${this.baseUrl}${path}`, this.ledgerOptions(path));
  }
}
